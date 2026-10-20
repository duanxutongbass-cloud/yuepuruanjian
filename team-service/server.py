"""Bounded JSON WSGI API, served by Waitress behind an explicit HTTPS proxy."""
import argparse
import getpass
from http import HTTPStatus
import json
import logging
import os
from pathlib import Path

from service import TeamService, Rejected, require
from catalog import Catalog, FileReply, MAX_PDF
from publishing import Publisher
from programs import Programs
from app_release import AppReleases
from annotations import Annotations, AnnotationReply, MAX_INK
from operation_records import TestOperations
from program_freezes import ProgramFreezes
from personal_folders import PersonalFolders, FolderReply, MAX_FOLDER_BODY
from score_numbers import capabilities

MAX_BODY = 16 * 1024


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result)
        result[key] = value
    return result


class Application:
    catalog_type = Catalog

    def check_protocol(self, env, method, path):
        pass

    def capability_reply(self, token, device, env):
        return capabilities(self.service, token, device)

    def __init__(self, service, allow_loopback_http=False):
        self.service = service
        self.catalog = self.catalog_type(service)
        self.publisher = Publisher(self.catalog)
        self.programs = Programs(service, self.catalog)
        self.catalog.programs = self.programs
        self.releases = AppReleases(service.directory)
        self.annotations = Annotations(service)
        self.program_freezes = ProgramFreezes(service, self.programs)
        self.programs.freezes = self.program_freezes
        self.test_operations = TestOperations(service, os.environ.get('TEST_OPERATIONS_ENABLED', '1') == '1')
        self.personal_folders = PersonalFolders(service)
        from permanent_storage import PermanentStorage
        self.permanent = None
        if isinstance(service, PermanentStorage):
            from permanent_deletion import PermanentDeletion
            from permanent_events import DeletionEvents
            self.permanent = PermanentDeletion(service, self.catalog)
            self.deletions = DeletionEvents(service)
            self.permanent.recover()
            from number_release import NumberRelease
            self.number_releases = NumberRelease(service,self.catalog)
        self.allow_loopback_http = allow_loopback_http

    def __call__(self, env, start_response):
        try:
            remote = env.get("REMOTE_ADDR", "")
            secure = env.get("wsgi.url_scheme") == "https"
            require(secure or self.allow_loopback_http and remote in ("127.0.0.1", "::1"), "https_required", 403)
            require(not env.get("QUERY_STRING") and not env.get("HTTP_X_MEMBER_ID") and
                    not env.get("HTTP_X_ACCOUNT_ID") and not env.get("HTTP_X_ROLE"), "identity_override_rejected", 403)
            require(not env.get("HTTP_ORIGIN"), "browser_origin_rejected", 403)
            method, path = env["REQUEST_METHOD"], env["PATH_INFO"]
            require(method in ("GET", "POST"), "method_not_allowed", 405)
            authorization = env.get("HTTP_AUTHORIZATION", "")
            token = authorization[7:] if authorization.startswith("Bearer ") else ""
            require(len(token) <= 128, "session_invalid", 401)
            device = env.get("HTTP_X_DEVICE_ID", "")
            public = {('POST', '/v1/auth/login'), ('POST', '/v1/auth/register'), ('POST', '/v1/auth/signup'),
                      ('GET', '/v1/app-release'), ('GET', '/v1/app-release/apk')}
            own_account = {('GET', '/v1/me'), ('GET', '/v1/me/devices'), ('POST', '/v1/me/devices/revoke'),
                           ('POST', '/v1/auth/logout'), ('POST', '/v1/auth/password'),
                           ('POST', '/v1/me/backup-consent'), ('GET', '/v1/me/membership'), ('POST', '/v1/me/membership')}
            if (method, path) not in public:
                # Fail closed before parsing team payloads. Modules re-authenticate
                # inside their transactions, including after long PDF uploads.
                with self.service.connection() as db:
                    self.service.authenticate(db, token, device, joined=(method, path) not in own_account)
            self.check_protocol(env, method, path)
            if path=='/v1/catalog' and self.permanent is not None and env.get('HTTP_X_SCORE_NUMBER_REUSE')!='1':
                from number_release import tables
                with self.service.connection() as db:
                    if tables(db):
                        require(not any(self.catalog.packed_metadata(db,r).get('pieceGeneration') for r in
                            db.execute('SELECT a.*,m.number,m.category FROM artifacts a JOIN artifact_metadata m ON m.artifact=a.id WHERE a.published=1')),
                            'reader_numbering_upgrade_required',426)
            body = None
            binary = method == "POST" and path.startswith("/v1/admin/uploads/") and path.endswith("/content")
            folder = path == '/v1/personal-folders'
            if method == 'POST' and folder:
                # Do not read a large image-bearing body before authenticating.
                # Dispatch authenticates again after upload, covering expiry/revocation.
                with self.service.connection() as db:
                    self.service.authenticate(db, token, device)
            if method == "POST" and not binary:
                require(env.get("CONTENT_TYPE", "").split(";")[0] == "application/json", "json_required", 415)
                length = int(env.get("CONTENT_LENGTH") or "0")
                annotation = path.startswith('/v1/annotations/')
                limit = MAX_FOLDER_BODY if folder else MAX_INK + MAX_BODY if annotation else MAX_BODY
                require(0 < length <= limit, "body_too_large", 413)
                raw = env["wsgi.input"].read(length)
                require(len(raw) == length)
                body = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object)
            if method == "GET" and path in ("/v1/app-release", "/v1/app-release/apk"):
                result = self.releases.reply(path, env.get("HTTP_IF_MATCH"))
            elif path == '/v1/admin/capabilities':
                require(method == 'GET', 'method_not_allowed', 405)
                result = self.capability_reply(token, device, env)
            elif folder:
                result = self.personal_folders.dispatch(method, path, body, token, device)
            elif path in ('/v1/test-operations', '/v1/admin/test-operations/query'):
                result = self.test_operations.dispatch(method, path, body, token, device)
            elif path == '/v1/admin/program-freezes' or path.startswith('/v1/program-freezes/'):
                result = self.program_freezes.dispatch(method, path, body, token, device)
            elif path == '/v1/annotations' or path.startswith('/v1/annotations/') or path == '/v1/annotation-backups' or path.startswith('/v1/annotation-backups/') or path == '/v1/admin/annotation-backups':
                result = self.annotations.dispatch(method, path, body, token, device)
            elif path in ('/v1/admin/number-releases','/v1/admin/number-releases/preview','/v1/admin/number-releases/commit'):
                require(self.permanent is not None,'not_found',404)
                if path=='/v1/admin/number-releases':
                    require(method=='GET','method_not_allowed',405)
                    result=self.number_releases.view(token,device)
                else:
                    require(method=='POST','method_not_allowed',405)
                    result=self.number_releases.preview(body,token,device) if path.endswith('/preview') else self.number_releases.commit(body,token,device)
            elif path in ('/v1/deletions', '/v1/admin/permanent-deletions/preview', '/v1/admin/permanent-deletions'):
                require(self.permanent is not None, 'not_found', 404)
                require(method == 'POST', 'method_not_allowed', 405)
                if path == '/v1/deletions':
                    result = self.deletions.page(body, token, device)
                elif path.endswith('/preview'):
                    result = dict(self.permanent.preview(body, token, device), permanentDeletionReady=True)
                else:
                    result = self.permanent.commit(body, token, device)
            elif path in ('/v1/admin/ensemble', '/v1/admin/programs', '/v1/admin/catalog-actions'):
                result = self.programs.dispatch(method, path, body, token, device)
            elif binary:
                require(env.get("CONTENT_TYPE") == "application/pdf", "pdf_required", 415)
                length = int(env.get("CONTENT_LENGTH") or "0")
                require(0 < length <= MAX_PDF, "body_too_large", 413)
                result = self.publisher.upload(path[len("/v1/admin/uploads/"):-len("/content")], env["wsgi.input"], length, token, device)
            elif (method, path) == ("POST", "/v1/admin/uploads"):
                result = self.publisher.prepare(body, token, device)
            elif (method, path) == ("POST", "/v1/admin/publications"):
                result = self.publisher.publish(body, token, device)
            elif (method, path) == ("POST", "/v1/admin/publication-cancellations"):
                result = self.publisher.cancel(body, token, device)
            elif path == "/v1/catalog" or path.startswith("/v1/artifacts/") or path in (
                    "/v1/admin/artifacts", "/v1/admin/artifacts/publish", "/v1/admin/grants", "/v1/admin/number-inventory"):
                result = self.catalog.dispatch(method, path, body, token, device, env.get("HTTP_RANGE"), env.get("HTTP_IF_MATCH"))
            else:
                result = self.service.dispatch(method, path, body, token, device, remote)
            status = result.status if isinstance(result, (AnnotationReply, FolderReply)) else 200
            if isinstance(result, (AnnotationReply, FolderReply)):
                result = result.data
        except Rejected as error:
            status, result = error.status, dict(error=error.code)
        except (ValueError, TypeError, KeyError, UnicodeError, RecursionError):
            status, result = 400, dict(error="invalid_request")
        except Exception:
            # Do not log request bodies, tokens, invitation codes, or database details.
            logging.getLogger(__name__).error("Team API operation failed; inspect server storage availability")
            status, result = 503, dict(error="service_unavailable")
        if isinstance(result, FileReply):
            headers = [("Content-Type", result.content_type), ("Content-Length", str(result.size)),
                       ("ETag", '"' + result.hash + '"'), ("Accept-Ranges", "bytes"),
                       ("Cache-Control", "no-store"), ("X-Content-Type-Options", "nosniff")]
            if result.partial:
                headers.append(("Content-Range", f"bytes {result.offset}-{result.offset + result.size - 1}/{result.total}"))
            try:
                start_response("206 Partial Content" if result.partial else "200 OK", headers)
                return result
            except BaseException:
                result.close()
                raise
        data = json.dumps(result, ensure_ascii=False).encode("utf-8")
        headers = [("Content-Type", "application/json; charset=utf-8"), ("Content-Length", str(len(data))),
                   ("Cache-Control", "no-store"), ("X-Content-Type-Options", "nosniff")]
        if status == 429:
            headers.append(("Retry-After", "900"))
        start_response(f"{status} {HTTPStatus(status).phrase}", headers)
        return [data]


def main():
    parser = argparse.ArgumentParser(description="Single-team score-library account service")
    parser.add_argument("--data", type=Path, required=True, help="Private persistent directory, outside source control")
    parser.add_argument("--permanent", action="store_true", help="Serve only explicitly activated v4 storage")
    parser.add_argument("--expanded", action="store_true", help="Serve explicitly activated naming-v2/v4 storage")
    parser.add_argument("--multi-team",action="store_true",help="Serve only an explicitly prepared multi-team root")
    commands = parser.add_subparsers(dest="command", required=True)
    multi_prepare=commands.add_parser('prepare-multi')
    multi_prepare.add_argument('--source',type=Path,required=True)
    multi_prepare.add_argument('--source-stopped',action='store_true',required=True)
    multi_prepare.add_argument('--confirmed',action='store_true',required=True)
    for name in ('restore-multi','verify-multi'):
        multi=commands.add_parser(name);multi.add_argument('--archive',type=Path,required=True)
    multi_activate=commands.add_parser('activate-multi')
    multi_activate.add_argument('--authority',type=Path,required=True)
    multi_activate.add_argument('--source-stopped',action='store_true',required=True)
    multi_activate.add_argument('--confirmed',action='store_true',required=True)
    prepare = commands.add_parser('prepare-permanent')
    prepare.add_argument('--archive',type=Path,required=True)
    activation = commands.add_parser('activate-permanent')
    activation.add_argument('--retirement-source',type=Path,required=True)
    activation.add_argument('--confirmed',action='store_true',required=True)
    activation.add_argument('--source-stopped',action='store_true',required=True)
    permanent_restore = commands.add_parser('restore-permanent')
    permanent_restore.add_argument('--archive',type=Path,required=True)
    permanent_verify = commands.add_parser('verify-permanent')
    permanent_verify.add_argument('--archive',type=Path,required=True)
    for name in ('prepare-expanded','restore-expanded','verify-expanded'):
        command=commands.add_parser(name)
        command.add_argument('--archive',type=Path,required=True)
    expanded_activation=commands.add_parser('activate-expanded')
    expanded_activation.add_argument('--retirement-source',type=Path,required=True)
    expanded_activation.add_argument('--confirmed',action='store_true',required=True)
    expanded_activation.add_argument('--source-stopped',action='store_true',required=True)
    init = commands.add_parser("init")
    init.add_argument("--team", required=True)
    init.add_argument("--username", required=True)
    init.add_argument("--name", required=True)
    reset = commands.add_parser("reset-password")
    reset.add_argument("--username", required=True)
    restore_device=commands.add_parser('restore-account-device')
    restore_device.add_argument('--username',required=True)
    restore_device.add_argument('--device-id',required=True)
    restore_device.add_argument('--confirmed',action='store_true',required=True)
    backup = commands.add_parser("backup")
    backup.add_argument("--output", type=Path, required=True)
    full = commands.add_parser("backup-full")
    full.add_argument("--output", type=Path, required=True)
    restore = commands.add_parser("restore-full")
    restore.add_argument("--archive", type=Path, required=True)
    verify = commands.add_parser("verify-backup")
    verify.add_argument("--archive", type=Path, required=True)
    stage = commands.add_parser("stage-part")
    release = commands.add_parser("publish-app")
    release.add_argument("--apk", type=Path, required=True)
    release.add_argument("--version-code", type=int, required=True)
    release.add_argument("--version-name", required=True)
    release.add_argument("--notes", default="")
    stage.add_argument("--pdf", type=Path, required=True)
    stage.add_argument("--title", required=True)
    stage.add_argument("--part", required=True)
    stage.add_argument("--independent-part", action="store_true", help="Confirm this entire PDF is one authorized part, not a combined score")
    serve = commands.add_parser("serve")
    serve.add_argument("--port", type=int, default=8787)
    serve.add_argument("--host", default="127.0.0.1")
    serve.add_argument("--trusted-proxy", help="Exact address of the TLS reverse proxy; no wildcard")
    serve.add_argument("--dev-loopback-http", action="store_true")
    args = parser.parse_args()
    os.umask(0o077)
    require(args.command!='restore-account-device' or args.multi_team,'multi_team_command_required')
    if args.multi_team or args.command in ('prepare-multi','restore-multi','verify-multi','activate-multi'):
        require(not args.expanded and not args.permanent,'multi_team_mode_conflict')
        from multiteam_cli import run
        run(args);return
    require(not (args.permanent and args.expanded),'explicit_storage_mode_required')
    if args.command in ('prepare-expanded','restore-expanded','verify-expanded','activate-expanded'):
        import expanded_permanent as expanded
        if args.command=='prepare-expanded':result=expanded.prepare(args.archive,args.data)
        elif args.command=='restore-expanded':result=expanded.restore_archive(args.archive,args.data)
        elif args.command=='activate-expanded':result=expanded.activate(args.data,args.retirement_source,args.confirmed and args.source_stopped)
        else:
            import tempfile
            with tempfile.TemporaryDirectory(prefix='expanded-verify-') as stage:
                result=expanded.validate_archive(args.archive,Path(stage))
        print(json.dumps(result));return
    if args.command in ('prepare-permanent','activate-permanent','restore-permanent','verify-permanent'):
        if args.command=='prepare-permanent':
            from permanent_storage import prepare_permanent_storage
            result=prepare_permanent_storage(args.archive,args.data)
        elif args.command=='activate-permanent':
            from permanent_runtime import activate
            result=activate(args.data,args.retirement_source,args.confirmed and args.source_stopped)
        elif args.command=='restore-permanent':
            from permanent_backup import restore_permanent_archive
            result=restore_permanent_archive(args.archive,args.data)
        else:
            from permanent_backup import validate_permanent_archive
            import tempfile
            with tempfile.TemporaryDirectory(prefix='permanent-verify-') as stage:
                result=validate_permanent_archive(args.archive,Path(stage))
        print(json.dumps(result));return
    if args.command in ("restore-full", "verify-backup"):
        from backup_archive import restore_archive, validate_archive
        if args.command == "restore-full":
            result = restore_archive(args.archive, args.data)
        else:
            import tempfile
            with tempfile.TemporaryDirectory(prefix="team-verify-") as stage:
                result = validate_archive(args.archive, Path(stage))
        print(json.dumps(result))
        return
    if args.expanded:
        from expanded_permanent import open_active
        service=open_active(args.data)
    elif args.permanent:
        from permanent_runtime import open_active
        service=open_active(args.data)
    else:
        service = TeamService(args.data)
    if args.command in ("init", "reset-password"):
        password = getpass.getpass("Password (12-128 characters): ")
        require(password == getpass.getpass("Confirm password: "), "password_mismatch")
        if args.command == "init":
            service.bootstrap(args.team, args.username, args.name, password)
        else:
            service.reset_password(args.username, password)
        print("Account operation completed.")
    elif args.command == "backup":
        service.backup(args.output)
        print("Consistent account database backup completed; protect this private file.")
    elif args.command == "backup-full":
        from backup_archive import create_archive
        if args.expanded:
            from expanded_permanent import create_archive
            create_archive(service,args.output)
        elif args.permanent:
            from permanent_backup import create_permanent_archive
            create_permanent_archive(service,args.output)
        else:create_archive(service, args.output)
        print("Verified account and PDF archive completed; protect this private file.")
    elif args.command == "publish-app":
        print(json.dumps(AppReleases(service.directory).publish(args.apk, args.version_code, args.version_name, args.notes)))
    elif args.command == "stage-part":
        require(not args.expanded,'expanded_requires_numbered_upload')
        print(Catalog(service).import_part(args.pdf, args.title, args.part, args.independent_part))
    else:
        from waitress import serve
        require(not args.dev_loopback_http or args.host == "127.0.0.1", "dev_requires_loopback")
        require(args.trusted_proxy != "*", "explicit_proxy_required")
        require(args.dev_loopback_http or args.trusted_proxy, "tls_proxy_required")
        options = dict(host=args.host, port=args.port, threads=4, channel_timeout=30,
                       max_request_body_size=MAX_PDF, max_request_header_size=8192,
                       clear_untrusted_proxy_headers=True)
        if args.trusted_proxy:
            options.update(trusted_proxy=args.trusted_proxy, trusted_proxy_count=1,
                           trusted_proxy_headers={"x-forwarded-proto"})
        print("Team API ready; HTTPS is required except explicit loopback development mode.", flush=True)
        if args.expanded:
            from expanded_permanent import ExpandedReadyApplication
            application=ExpandedReadyApplication(service,args.dev_loopback_http)
        else:application=Application(service,args.dev_loopback_http)
        serve(application, **options)


if __name__ == "__main__":
    main()
