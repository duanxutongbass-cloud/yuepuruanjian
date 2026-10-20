"""Single-team account service. Identity comes exclusively from bound sessions.

All mutable authorization and credential changes share SQLite transactions.
The local Android member selector is never accepted as a server credential.
"""
from contextlib import contextmanager, closing
import json
import hmac
import re
import secrets
import sqlite3
import time
import uuid
from pathlib import Path

from security import digest, password_hash, password_matches

# The client may use its last verified grants for at most seven days. Only an
# authenticated /me check renews this deadline; ordinary transfers do not.
SESSION_SECONDS = 7 * 24 * 60 * 60
CONSENT_VERSION = "team-annotation-backup-v1"
USERNAME = re.compile(r"[a-z0-9][a-z0-9._-]{2,63}")
UUID = re.compile(r"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
JOIN_CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"


def new_join_code(previous_hash):
    for _ in range(32):
        code = ''.join(secrets.choice(JOIN_CODE_ALPHABET) for _ in range(6))
        if any(c.isdigit() for c in code) and any(c.isalpha() for c in code) and digest(code) != previous_hash:
            return code
    raise Rejected(503, "join_code_generation_failed")


def available_short_code(db, previous_hash):
    # A public team code must never also be a one-person invitation that skips
    # review. Retain consumed hashes too, so a short code is never recycled.
    for _ in range(32):
        code = new_join_code(previous_hash)
        if not db.execute('SELECT 1 FROM invitations WHERE hash=?', (digest(code),)).fetchone():
            return code
    raise Rejected(503, 'invitation_generation_failed')


class Rejected(Exception):
    def __init__(self, status, code):
        super().__init__(code)
        self.status, self.code = status, code


def require(condition, code="invalid_request", status=400):
    if not condition:
        raise Rejected(status, code)


def fields(body, required, optional=()):
    require(isinstance(body, dict) and set(required) <= set(body) <= set(required) | set(optional))


def label(value, maximum=100):
    require(isinstance(value, str) and 1 <= len(value.strip()) <= maximum and
            not any(ord(c) < 32 for c in value))
    return value.strip()


def username(value):
    require(isinstance(value, str))
    value = value.strip().lower()
    require(USERNAME.fullmatch(value) is not None, "invalid_username")
    return value


class TeamService:
    def __init__(self, directory, clock=time.time):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.path = self.directory / "team.sqlite3"
        self.clock = clock
        # A missing account still incurs one real password calculation.
        self.dummy = password_hash(secrets.token_urlsafe(32))
        with self.connection() as db:
            version = db.execute("PRAGMA user_version").fetchone()[0]
            require(version in (0, 1, 2), "unsupported_database", 503)
            db.executescript("""
                CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS accounts(
                    id TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL, name TEXT NOT NULL,
                    password TEXT NOT NULL, role TEXT NOT NULL CHECK(role IN ('admin','member')),
                    active INTEGER NOT NULL DEFAULT 1, consent TEXT, created INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS devices(
                    account TEXT NOT NULL REFERENCES accounts(id), id TEXT NOT NULL, name TEXT NOT NULL,
                    revoked INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, seen INTEGER NOT NULL,
                    PRIMARY KEY(account,id));
                CREATE TABLE IF NOT EXISTS sessions(
                    hash TEXT PRIMARY KEY, account TEXT NOT NULL, device TEXT NOT NULL,
                    expires INTEGER NOT NULL, FOREIGN KEY(account,device) REFERENCES devices(account,id));
                CREATE INDEX IF NOT EXISTS session_owner ON sessions(account,device);
                CREATE TABLE IF NOT EXISTS invitations(
                    hash TEXT PRIMARY KEY, role TEXT NOT NULL, expires INTEGER NOT NULL,
                    used INTEGER NOT NULL DEFAULT 0, creator TEXT NOT NULL REFERENCES accounts(id));
                CREATE TABLE IF NOT EXISTS audit(
                    id INTEGER PRIMARY KEY, at INTEGER NOT NULL, actor TEXT, event TEXT NOT NULL, target TEXT);
                CREATE TABLE IF NOT EXISTS throttle(
                    key TEXT PRIMARY KEY, start INTEGER NOT NULL, count INTEGER NOT NULL);
            """)
            # An atomic extension, without rewriting accounts, grants or original files.
            # Version 2 also makes older servers refuse this database: a downgrade
            # must not interpret a newly registered applicant as an existing member.
            db.execute("BEGIN IMMEDIATE")
            # executescript releases the first transaction. Another process may
            # have completed this migration before we acquired the lock again.
            version = db.execute("PRAGMA user_version").fetchone()[0]
            require(version in (0, 1, 2), "unsupported_database", 503)
            if version < 2:
                db.execute("""CREATE TABLE account_memberships(
                    account TEXT PRIMARY KEY REFERENCES accounts(id),
                    state TEXT NOT NULL CHECK(state IN ('unjoined','pending','rejected','joined')),
                    revision INTEGER NOT NULL CHECK(revision>=0), requested INTEGER,
                    reviewed INTEGER, reviewer TEXT REFERENCES accounts(id))""")
                db.execute("INSERT INTO account_memberships(account,state,revision) SELECT id,'joined',0 FROM accounts")
                db.execute("""CREATE TABLE join_code(
                    singleton INTEGER PRIMARY KEY CHECK(singleton=1), hash TEXT,
                    revision INTEGER NOT NULL CHECK(revision>=0))""")
                db.execute("INSERT INTO join_code VALUES(1,NULL,0)")
                db.execute("""CREATE TABLE membership_operations(
                    actor TEXT NOT NULL REFERENCES accounts(id), request TEXT NOT NULL,
                    fingerprint TEXT NOT NULL, result TEXT NOT NULL, PRIMARY KEY(actor,request))""")
                db.execute("PRAGMA user_version=2")
            require(not db.execute("""SELECT 1 FROM accounts a LEFT JOIN account_memberships m
                ON m.account=a.id WHERE m.account IS NULL LIMIT 1""").fetchone(), "membership_unavailable", 503)
            require(db.execute("SELECT count(*) FROM join_code WHERE singleton=1").fetchone()[0] == 1,
                    "membership_unavailable", 503)

    @contextmanager
    def connection(self):
        db = sqlite3.connect(self.path, timeout=15, isolation_level=None)
        db.row_factory = sqlite3.Row
        try:
            db.execute("PRAGMA foreign_keys=ON")
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("PRAGMA synchronous=FULL")
            db.execute("BEGIN IMMEDIATE")
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    def audit(self, db, actor, event, target=None):
        db.execute("INSERT INTO audit(at,actor,event,target) VALUES(?,?,?,?)",
                   (int(self.clock()), actor, event, target))

    def bootstrap(self, team_name, account, name, password):
        team_name, account, name = label(team_name), username(account), label(name)
        encoded = password_hash(password)
        with self.connection() as db:
            require(db.execute("SELECT COUNT(*) FROM accounts").fetchone()[0] == 0,
                    "already_initialized", 409)
            account_id, team_id = str(uuid.uuid4()), str(uuid.uuid4())
            db.executemany("INSERT INTO metadata VALUES(?,?)", [("teamId", team_id), ("teamName", team_name)])
            db.execute("INSERT INTO accounts(id,username,name,password,role,created) VALUES(?,?,?,?,?,?)",
                       (account_id, account, name, encoded, "admin", int(self.clock())))
            db.execute("INSERT INTO account_memberships(account,state,revision) VALUES(?,'joined',0)", (account_id,))
            self.audit(db, account_id, "bootstrap")
            return account_id

    def throttle(self, remote, identity):
        # Commit attempts independently: failed authentication must not roll back its limit.
        now = int(self.clock())
        with self.connection() as db:
            db.execute("DELETE FROM throttle WHERE start < ?", (now - 900,))
            keys = [(digest("ip/" + remote), 40), (digest("account/" + identity), 10)]
            for key, maximum in keys:
                row = db.execute("SELECT count FROM throttle WHERE key=?", (key,)).fetchone()
                require(row is None or row[0] < maximum, "try_later", 429)
            for key, _ in keys:
                db.execute("INSERT INTO throttle VALUES(?,?,1) ON CONFLICT(key) DO UPDATE SET count=count+1", (key, now))

    def principal(self, db, account):
        meta = dict(db.execute("SELECT key,value FROM metadata"))
        return dict(accountId=account["id"], memberId=account["id"], username=account["username"],
                    displayName=account["name"], role=account["role"], teamId=meta["teamId"],
                    teamName=meta["teamName"], backupConsent=account["consent"] == CONSENT_VERSION,
                    membership=self.membership(db, account["id"])["state"])

    def membership(self, db, aid):
        row = db.execute("SELECT * FROM account_memberships WHERE account=?", (aid,)).fetchone()
        require(row is not None, "membership_unavailable", 503)
        return dict(state=row["state"], revision=row["revision"], requestedAt=row["requested"],
                    reviewedAt=row["reviewed"])

    def require_member(self, db, aid):
        require(self.membership(db, aid)["state"] == "joined", "membership_required", 403)

    def recipient(self, db, aid):
        return db.execute("""SELECT 1 FROM accounts a JOIN account_memberships m ON m.account=a.id
            WHERE a.id=? AND a.active=1 AND m.state='joined'""", (aid,)).fetchone() is not None

    def authenticate(self, db, token, device, *, joined=True):
        require(isinstance(token, str) and 32 <= len(token) <= 128 and
                isinstance(device, str) and UUID.fullmatch(device), "session_invalid", 401)
        account = db.execute("""SELECT a.*, s.expires FROM sessions s JOIN accounts a ON a.id=s.account
            JOIN devices d ON d.account=s.account AND d.id=s.device
            WHERE s.hash=? AND s.device=? AND s.expires>? AND a.active=1 AND d.revoked=0""",
                             (digest(token), device, int(self.clock()))).fetchone()
        require(account is not None, "session_invalid", 401)
        if joined:
            self.require_member(db, account["id"])
        return account

    def issue(self, db, account, body):
        device, name = body.get("deviceId"), label(body.get("deviceName"), 80)
        require(isinstance(device, str) and UUID.fullmatch(device), "invalid_device")
        now = int(self.clock())
        row = db.execute("SELECT revoked FROM devices WHERE account=? AND id=?", (account["id"], device)).fetchone()
        require(row is None or row[0] == 0, "device_revoked", 403)
        db.execute("""INSERT INTO devices VALUES(?,?,?,0,?,?) ON CONFLICT(account,id)
                   DO UPDATE SET name=excluded.name, seen=excluded.seen""", (account["id"], device, name, now, now))
        db.execute("DELETE FROM sessions WHERE expires<=? OR (account=? AND device=?)", (now, account["id"], device))
        token = secrets.token_urlsafe(32)
        db.execute("INSERT INTO sessions VALUES(?,?,?,?)", (digest(token), account["id"], device, now + SESSION_SECONDS))
        self.audit(db, account["id"], "login", device)
        return dict(token=token, expiresAt=now + SESSION_SECONDS, principal=self.principal(db, account))

    def login(self, body, remote):
        fields(body, {"username", "password", "deviceId", "deviceName"})
        account_name = username(body["username"])
        self.throttle(remote, account_name)
        with self.connection() as db:
            account = db.execute("SELECT * FROM accounts WHERE username=?", (account_name,)).fetchone()
            matched = password_matches(body["password"], account["password"] if account else self.dummy)
            require(matched and account is not None and account["active"], "login_failed", 401)
            return self.issue(db, account, body)

    @staticmethod
    def invitation_value(value):
        # New short codes are easy to type; existing long tokens keep their
        # original case-sensitive identity until used or expired.
        if len(value) == 6 and all(c in JOIN_CODE_ALPHABET for c in value.upper()):
            return value.upper()
        return value

    def register(self, body, remote):
        fields(body, {"username", "password", "displayName", "invitation", "deviceId", "deviceName", "backupConsent"})
        account_name, name = username(body["username"]), label(body["displayName"])
        require(body["backupConsent"] is True, "consent_required")
        require(isinstance(body["invitation"], str) and len(body["invitation"]) <= 128)
        self.throttle(remote, account_name)
        with self.connection() as db:
            invite = db.execute("SELECT * FROM invitations WHERE hash=? AND used=0 AND expires>?",
                                (digest(self.invitation_value(body["invitation"])), int(self.clock()))).fetchone()
            require(invite is not None, "invitation_invalid", 403)
            require(db.execute("SELECT id FROM accounts WHERE username=?", (account_name,)).fetchone() is None,
                    "username_unavailable", 409)
            try:
                encoded = password_hash(body["password"])
            except ValueError:
                raise Rejected(400, "password_length") from None
            account_id = str(uuid.uuid4())
            db.execute("INSERT INTO accounts(id,username,name,password,role,consent,created) VALUES(?,?,?,?,?,?,?)",
                       (account_id, account_name, name, encoded, invite["role"], CONSENT_VERSION, int(self.clock())))
            db.execute("INSERT INTO account_memberships(account,state,revision) VALUES(?,'joined',0)", (account_id,))
            db.execute("UPDATE invitations SET used=1 WHERE hash=?", (invite["hash"],))
            self.audit(db, account_id, "register", invite["creator"])
            self.audit(db, account_id, "backup_consent", CONSENT_VERSION)
            return self.issue(db, db.execute("SELECT * FROM accounts WHERE id=?", (account_id,)).fetchone(), body)

    def signup(self, body, remote):
        fields(body, {"username", "password", "displayName", "deviceId", "deviceName", "backupConsent"})
        account_name, name = username(body["username"]), label(body["displayName"])
        require(type(body["backupConsent"]) is bool)
        self.throttle(remote, account_name)
        with self.connection() as db:
            require(db.execute("SELECT 1 FROM metadata WHERE key='teamId'").fetchone(), "not_initialized", 503)
            require(db.execute("SELECT 1 FROM accounts WHERE username=?", (account_name,)).fetchone() is None,
                    "username_unavailable", 409)
            try:
                encoded = password_hash(body["password"])
            except ValueError:
                raise Rejected(400, "password_length") from None
            aid = str(uuid.uuid4())
            consent = CONSENT_VERSION if body["backupConsent"] else None
            db.execute("INSERT INTO accounts(id,username,name,password,role,consent,created) VALUES(?,?,?,?,'member',?,?)",
                       (aid, account_name, name, encoded, consent, int(self.clock())))
            db.execute("INSERT INTO account_memberships(account,state,revision) VALUES(?,'unjoined',0)", (aid,))
            self.audit(db, aid, "public_registered")
            if consent:
                self.audit(db, aid, "backup_consent", CONSENT_VERSION)
            # Invalid/revoked device validation in issue rolls back this whole registration.
            return self.issue(db, db.execute("SELECT * FROM accounts WHERE id=?", (aid,)).fetchone(), body)

    def membership_operation(self, db, aid, body, action, perform):
        require(isinstance(body["requestId"], str) and UUID.fullmatch(body["requestId"]))
        require(type(body["baseRevision"]) is int and 0 <= body["baseRevision"] < 2**53)
        fingerprint = digest(action + "/" + json.dumps(body, sort_keys=True, separators=(',', ':')))
        old = db.execute("SELECT * FROM membership_operations WHERE actor=? AND request=?",
                         (aid, body["requestId"])).fetchone()
        if old:
            require(old["fingerprint"] == fingerprint, "request_reused", 409)
            return json.loads(old["result"])
        result = perform()
        db.execute("INSERT INTO membership_operations VALUES(?,?,?,?)",
                   (aid, body["requestId"], fingerprint, json.dumps(result, separators=(',', ':'))))
        return result

    def apply_to_team(self, db, aid, body):
        fields(body, {"requestId", "baseRevision", "code"})
        require(isinstance(body["code"], str) and 1 <= len(body["code"]) <= 128)
        def apply():
            current = self.membership(db, aid)
            require(current["revision"] == body["baseRevision"], "membership_changed", 409)
            require(current["state"] in ("unjoined", "rejected"), "application_exists", 409)
            code = db.execute("SELECT hash FROM join_code WHERE singleton=1").fetchone()[0]
            supplied = body["code"].strip()
            # Only the new short alphabet is case-insensitive. Existing long
            # case-sensitive codes remain valid until explicitly rotated.
            if len(supplied) == 6 and all(c in JOIN_CODE_ALPHABET for c in supplied.upper()):
                supplied = supplied.upper()
            require(code is not None and hmac.compare_digest(code, digest(supplied)), "join_code_invalid", 403)
            db.execute("""UPDATE account_memberships SET state='pending',revision=revision+1,
                requested=?,reviewed=NULL,reviewer=NULL WHERE account=?""", (int(self.clock()), aid))
            self.audit(db, aid, "join_requested")
            return self.membership(db, aid)
        return self.membership_operation(db, aid, body, "apply", apply)

    def review_application(self, db, actor, body):
        fields(body, {"requestId", "baseRevision", "accountId", "approved"})
        require(type(body["approved"]) is bool and isinstance(body["accountId"], str) and UUID.fullmatch(body["accountId"]))
        def review():
            target = db.execute("SELECT active FROM accounts WHERE id=?", (body["accountId"],)).fetchone()
            require(target is not None, "not_found", 404)
            current = self.membership(db, body["accountId"])
            require(current["revision"] == body["baseRevision"] and current["state"] == "pending",
                    "membership_changed", 409)
            require(target["active"], "recipient_unavailable", 409)
            state = "joined" if body["approved"] else "rejected"
            db.execute("UPDATE account_memberships SET state=?,revision=revision+1,reviewed=?,reviewer=? WHERE account=?",
                       (state, int(self.clock()), actor["id"], body["accountId"]))
            self.audit(db, actor["id"], "join_approved" if body["approved"] else "join_rejected", body["accountId"])
            return self.membership(db, body["accountId"])
        return self.membership_operation(db, actor["id"], body, "review", review)

    def dispatch(self, method, path, body, token, device, remote):
        if (method, path) == ("POST", "/v1/auth/login"):
            return self.login(body, remote)
        if (method, path) == ("POST", "/v1/auth/register"):
            return self.register(body, remote)
        if (method, path) == ("POST", "/v1/auth/signup"):
            return self.signup(body, remote)
        if method == "POST" and path in ("/v1/auth/password", "/v1/me/membership"):
            self.throttle(remote, digest(token) + path)
        with self.connection() as db:
            actor = self.authenticate(db, token, device, joined=False)
            aid = actor["id"]
            if (method, path) == ("GET", "/v1/me"):
                expires = int(self.clock()) + SESSION_SECONDS
                db.execute("UPDATE sessions SET expires=? WHERE hash=?", (expires, digest(token)))
                db.execute("UPDATE devices SET seen=? WHERE account=? AND id=?", (int(self.clock()), aid, device))
                return dict(principal=self.principal(db, actor), expiresAt=expires)
            if (method, path) == ("POST", "/v1/auth/logout"):
                fields(body, set())
                db.execute("DELETE FROM sessions WHERE hash=?", (digest(token),))
                self.audit(db, aid, "logout", device)
                return dict(ok=True)
            if (method, path) == ("POST", "/v1/auth/password"):
                fields(body, {"currentPassword", "newPassword"})
                require(password_matches(body["currentPassword"], actor["password"]), "login_failed", 401)
                try:
                    encoded = password_hash(body["newPassword"])
                except ValueError:
                    raise Rejected(400, "password_length") from None
                db.execute("UPDATE accounts SET password=? WHERE id=?", (encoded, aid))
                db.execute("DELETE FROM sessions WHERE account=?", (aid,))
                self.audit(db, aid, "password_changed")
                return dict(ok=True)
            if (method, path) == ("POST", "/v1/me/backup-consent"):
                fields(body, {"accepted"})
                require(body["accepted"] is True, "consent_required")
                db.execute("UPDATE accounts SET consent=? WHERE id=?", (CONSENT_VERSION, aid))
                self.audit(db, aid, "backup_consent", CONSENT_VERSION)
                return dict(ok=True)
            if (method, path) == ("GET", "/v1/me/devices"):
                return dict(devices=[dict(id=r["id"], name=r["name"], revoked=bool(r["revoked"]),
                                          lastSeen=r["seen"], current=r["id"] == device)
                                     for r in db.execute("SELECT * FROM devices WHERE account=? ORDER BY seen DESC", (aid,))])
            if (method, path) == ("POST", "/v1/me/devices/revoke"):
                fields(body, {"deviceId"})
                target = body["deviceId"]
                require(isinstance(target, str) and UUID.fullmatch(target))
                require(db.execute("SELECT 1 FROM devices WHERE account=? AND id=?", (aid, target)).fetchone(), "not_found", 404)
                db.execute("UPDATE devices SET revoked=1 WHERE account=? AND id=?", (aid, target))
                db.execute("DELETE FROM sessions WHERE account=? AND device=?", (aid, target))
                self.audit(db, aid, "device_revoked", target)
                return dict(ok=True)
            if path == "/v1/me/membership":
                if method == "GET":
                    return self.membership(db, aid)
                if method == "POST":
                    return self.apply_to_team(db, aid, body)
            # All remaining account/admin routes require approved membership.
            self.require_member(db, aid)
            # Every admin route checks the persisted role on this request, not a submitted role.
            if path.startswith("/v1/admin/"):
                require(actor["role"] == "admin", "forbidden", 403)
                if path == "/v1/admin/join-code":
                    current = db.execute("SELECT * FROM join_code WHERE singleton=1").fetchone()
                    if method == "GET":
                        return dict(configured=current["hash"] is not None, revision=current["revision"])
                    if method == "POST":
                        fields(body, {"baseRevision"})
                        require(type(body["baseRevision"]) is int and body["baseRevision"] >= 0)
                        require(body["baseRevision"] == current["revision"], "join_code_changed", 409)
                        code = available_short_code(db, current["hash"])
                        db.execute("UPDATE join_code SET hash=?,revision=revision+1 WHERE singleton=1", (digest(code),))
                        self.audit(db, aid, "join_code_rotated")
                        # Plaintext is shown once, never saved in the database or audit.
                        return dict(code=code, revision=current["revision"] + 1, configured=True)
                if (method, path) == ("GET", "/v1/admin/join-requests"):
                    rows = db.execute("""SELECT a.id,a.username,a.name,m.* FROM accounts a
                        JOIN account_memberships m ON m.account=a.id WHERE m.state='pending'
                        AND a.active=1 ORDER BY m.requested,a.id LIMIT 200""").fetchall()
                    total = db.execute("""SELECT count(*) FROM account_memberships m JOIN accounts a ON a.id=m.account
                        WHERE m.state='pending' AND a.active=1""").fetchone()[0]
                    return dict(requests=[dict(accountId=r["id"], username=r["username"], displayName=r["name"],
                        revision=r["revision"], requestedAt=r["requested"]) for r in rows], total=total)
                if (method, path) == ("POST", "/v1/admin/join-requests/review"):
                    return self.review_application(db, actor, body)
                if (method, path) == ("POST", "/v1/admin/invitations"):
                    fields(body, set())
                    previous = db.execute('SELECT hash FROM join_code WHERE singleton=1').fetchone()[0]
                    invite = available_short_code(db, previous)
                    expires = int(self.clock()) + 48 * 3600
                    db.execute("INSERT INTO invitations VALUES(?,?,?,0,?)", (digest(invite), "member", expires, aid))
                    self.audit(db, aid, "invitation_created")
                    return dict(invitation=invite, expiresAt=expires)
                if (method, path) == ("GET", "/v1/admin/members"):
                    return dict(members=[dict(accountId=r["id"], username=r["username"], displayName=r["name"],
                                               role=r["role"], active=bool(r["active"]))
                                         for r in db.execute("""SELECT a.* FROM accounts a JOIN account_memberships m
                                             ON m.account=a.id WHERE m.state='joined' ORDER BY a.created,a.id""")])
                if (method, path) == ("POST", "/v1/admin/members/status"):
                    fields(body, {"accountId", "active"})
                    require(type(body["active"]) is bool and isinstance(body["accountId"], str))
                    target = db.execute("SELECT * FROM accounts WHERE id=?", (body["accountId"],)).fetchone()
                    require(target is not None, "not_found", 404)
                    require(target["role"] != "admin", "admin_status_requires_operator", 409)
                    db.execute("UPDATE accounts SET active=? WHERE id=?", (int(body["active"]), target["id"]))
                    db.execute("DELETE FROM sessions WHERE account=?", (target["id"],))
                    self.audit(db, aid, "member_enabled" if body["active"] else "member_disabled", target["id"])
                    return dict(ok=True)
                if (method, path) == ("POST", "/v1/admin/devices/restore"):
                    fields(body, {"accountId", "deviceId"})
                    require(isinstance(body["accountId"], str) and isinstance(body["deviceId"], str))
                    result = db.execute("UPDATE devices SET revoked=0 WHERE account=? AND id=?", (body["accountId"], body["deviceId"]))
                    require(result.rowcount == 1, "not_found", 404)
                    self.audit(db, aid, "device_restored", body["accountId"] + "/" + body["deviceId"])
                    return dict(ok=True)
                if (method, path) == ("GET", "/v1/admin/audit"):
                    return dict(events=[dict(r) for r in db.execute("SELECT * FROM audit ORDER BY id DESC LIMIT 200")])
            raise Rejected(404, "not_found")

    def reset_password(self, account_name, password):
        encoded = password_hash(password)
        with self.connection() as db:
            row = db.execute("SELECT id FROM accounts WHERE username=?", (username(account_name),)).fetchone()
            require(row is not None, "not_found", 404)
            db.execute("UPDATE accounts SET password=? WHERE id=?", (encoded, row[0]))
            db.execute("DELETE FROM sessions WHERE account=?", (row[0],))
            self.audit(db, None, "operator_password_reset", row[0])

    def backup(self, target):
        target = Path(target)
        require(not target.exists(), "backup_target_exists", 409)
        # SQLite backup API includes committed WAL contents; copying the .db alone does not.
        with closing(sqlite3.connect(self.path)) as source, closing(sqlite3.connect(target)) as dest:
            source.backup(dest)
            # A restored backup must not revive previously logged-out/revoked bearer tokens.
            dest.execute("DELETE FROM sessions")
            dest.commit()
            require(dest.execute("PRAGMA integrity_check").fetchone()[0] == "ok", "backup_invalid", 500)
