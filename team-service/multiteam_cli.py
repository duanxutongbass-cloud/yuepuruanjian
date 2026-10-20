"""Explicit multi-team operator entry points. Never falls back to a single-team root."""
from pathlib import Path
import json,tempfile
from service import require
from multiteam import MultiTeamDirectory,MultiTeamApplication
import multiteam_storage as storage


def run(args):
    if args.command=='prepare-multi':
        require(args.confirmed and args.source_stopped,'multi_team_source_stop_required')
        print(json.dumps(storage.prepare(args.source,args.data)));return
    if args.command=='restore-multi':print(json.dumps(storage.restore_archive(args.archive,args.data)));return
    if args.command=='activate-multi':
        require(args.confirmed and args.source_stopped,'multi_team_source_stop_required')
        print(json.dumps(storage.activate(args.data,args.authority)));return
    if args.command=='verify-multi':
        with tempfile.TemporaryDirectory(prefix='multi-verify-') as tmp:
            manifest=storage.validate_archive(args.archive,Path(tmp));print(json.dumps(dict(teams=len(manifest['files'])-1,verified=True)))
        return
    directory=MultiTeamDirectory(args.data)
    if args.command=='restore-account-device':
        from service import UUID,username
        require(args.confirmed and UUID.fullmatch(args.device_id),'invalid_device')
        with directory.locked(),directory.identity.connection() as db:
            account=db.execute('SELECT id FROM accounts WHERE username=?',(username(args.username),)).fetchone()
            require(account is not None,'not_found',404)
            result=db.execute('UPDATE devices SET revoked=0 WHERE account=? AND id=?',(account[0],args.device_id))
            require(result.rowcount==1,'not_found',404)
            db.execute('DELETE FROM sessions WHERE account=? AND device=?',(account[0],args.device_id))
            directory.identity.audit(db,None,'operator_device_restored',account[0]+'/'+args.device_id)
        print('Device restored for next password login; no session issued.');return
    if args.command=='backup-full':print(json.dumps(directory.backup(args.output)));return
    if args.command=='publish-app':
        print(json.dumps(directory.releases.publish(args.apk,args.version_code,args.version_name,args.notes)));return
    if args.command=='reset-password':
        import getpass
        password=getpass.getpass('Password (12-128 characters): ')
        require(password==getpass.getpass('Confirm password: '),'password_mismatch')
        with directory.locked():directory.identity.reset_password(args.username,password)
        print('Account operation completed.');return
    require(args.command=='serve','multi_team_command_required')
    require(not args.dev_loopback_http or args.host=='127.0.0.1','dev_requires_loopback')
    require(args.trusted_proxy!='*' and (args.dev_loopback_http or args.trusted_proxy),'tls_proxy_required')
    from waitress import serve
    options=dict(host=args.host,port=args.port,threads=4,channel_timeout=30,max_request_body_size=100*1024*1024,
        max_request_header_size=8192,clear_untrusted_proxy_headers=True)
    if args.trusted_proxy:options.update(trusted_proxy=args.trusted_proxy,trusted_proxy_count=1,trusted_proxy_headers={'x-forwarded-proto'})
    print('Multi-team API ready; session-bound teams and HTTPS required.',flush=True)
    serve(MultiTeamApplication(directory,args.dev_loopback_http),**options)
