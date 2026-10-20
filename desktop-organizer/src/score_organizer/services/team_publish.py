"""Optional team publishing. Local archive records and original PDFs remain read-only."""
from __future__ import annotations

from score_organizer.domain.score_naming import format_score_number

from dataclasses import dataclass, replace
import hashlib
import http.client
import json
from pathlib import Path
import ssl
from urllib.parse import urlsplit
import uuid

from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.import_service import sha256_file
from score_organizer.services.program_service import reject_links
from score_organizer.services.pdf_upload_compat import PdfInputError, UploadIssue, UploadPdf, UploadPdfWorkspace

MAX_PDF = 100 * 1024 * 1024
MAX_PUBLICATION_ITEMS = 50
MAX_REQUEST_BYTES = 16 * 1024

SERVER_REASONS = {
    'invalid_pdf': '服务无法识别此 PDF',
    'invalid_page_size': '服务拒绝了 PDF 页面尺寸',
    'invalid_page_count': '服务不支持此 PDF 页数',
    'encrypted_pdf_not_supported': '服务不支持加密 PDF',
    'active_or_embedded_pdf_rejected': '服务拒绝了 PDF 中的活动或嵌入内容',
    'invalid_request': '服务未接受本次请求参数',
    'size_mismatch': '服务收到的文件长度不一致',
    'upload_incomplete': '服务未收到完整文件',
}


class TeamRequestError(ValueError):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


def _unique_json_object(pairs):
    result = {}
    for name, value in pairs:
        if name in result:
            raise ValueError('服务响应包含重复字段，未采用含糊的能力或发布回执')
        result[name] = value
    return result


class TeamClient:
    def __init__(self, address, device_id, *, allow_test_http=False):
        value = urlsplit(address.strip())
        if (value.scheme != 'https' and not (allow_test_http and value.scheme == 'http' and value.hostname == '127.0.0.1')) or not value.hostname or value.username or value.password or value.query or value.fragment or value.path not in ('', '/'):
            raise ValueError('请输入独立的 HTTPS 服务地址，不含账号、路径或查询参数')
        self.address = value.geturl().rstrip('/')
        self.host, self.port, self.secure = value.hostname, value.port, value.scheme == 'https'
        self.device_id = str(uuid.UUID(device_id))
        self.token = ''
        self.principal = None

    def request(self, method, path, body=None, file=None, size=None):
        connection = (http.client.HTTPSConnection(self.host, self.port, timeout=45, context=ssl.create_default_context())
                      if self.secure else http.client.HTTPConnection(self.host, self.port, timeout=45))
        try:
            headers = {'X-Device-ID': self.device_id, 'Accept': 'application/json'}
            if self.token:
                headers['Authorization'] = 'Bearer ' + self.token
                if self.principal:headers['X-Score-Team']=self.principal['teamId']
            if file is not None:
                headers.update({'Content-Type': 'application/pdf', 'Content-Length': str(size)})
                connection.putrequest(method, path)
                for key, value in headers.items():
                    connection.putheader(key, value)
                connection.endheaders()
                remaining = size
                with Path(file).open('rb') as source:
                    while remaining:
                        chunk = source.read(min(65536, remaining))
                        if not chunk:
                            raise ValueError('上传期间文件发生变化，请重新预览')
                        connection.send(chunk)
                        remaining -= len(chunk)
                    if source.read(1):
                        raise ValueError('上传期间文件大小发生变化，请重新预览')
            else:
                data = None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8')
                if data is not None:
                    if len(data) > 16 * 1024:
                        raise ValueError('发布清单过大，请减少分谱或接收成员后分批发布')
                    headers['Content-Type'] = 'application/json'
                connection.request(method, path, data, headers)
            response = connection.getresponse()
            raw = response.read(256 * 1024 + 1)
            if len(raw) > 256 * 1024:
                raise ValueError('服务响应过大')
            if response.status != 200:
                # Never echo server text, credentials, or redirects into the UI/log.
                if response.status == 401:
                    self.token, self.principal = '', None
                descriptions = {401:'登录已失效，请重新登录',403:'没有操作权限或服务未正确启用 HTTPS',
                                409:'数据已变化或发布存在冲突，请核对后重试',413:'文件或清单超过服务器限制',
                                415:'服务器不支持此上传格式',429:'操作过于频繁，请稍后重试'}
                message = descriptions.get(response.status, f'服务请求失败（HTTP {response.status}），未自动跳转到其他地址')
                try:
                    code = json.loads(raw, object_pairs_hook=_unique_json_object).get('error')
                    if isinstance(code, str) and code in SERVER_REASONS:
                        message += '；' + SERVER_REASONS[code] + f'（{code}）'
                except (ValueError, TypeError, AttributeError):
                    pass
                raise TeamRequestError(response.status, message)
            result = json.loads(raw, object_pairs_hook=_unique_json_object)
            if not isinstance(result, dict):
                raise ValueError('服务响应格式不正确')
            return result
        except (OSError, http.client.HTTPException) as exc:
            raise ValueError('连接失败或传输中断；请检查服务地址和网络，可重新登录后重试相同发布') from exc
        finally:
            connection.close()

    def login(self, username, password):
        self.token, self.principal = '', None
        reply = self.request('POST', '/v1/auth/login', dict(username=username, password=password,
                             deviceId=self.device_id, deviceName='Windows 乐谱整理器'))
        self.token, self.principal = reply['token'], reply['principal']
        if self.principal['role'] != 'admin' and self.principal.get('multiTeamProtocol')!=1:
            self.logout()
            raise ValueError('仅团队管理员可以发布乐谱')
        return self.principal

    def teams(self):
        if not self.principal or self.principal.get('multiTeamProtocol')!=1:return []
        reply=self.request('GET','/v1/me/teams')
        if (reply.get('protocolVersion')!=1 or reply.get('accountId')!=self.principal['accountId']
                or reply.get('currentTeamId')!=self.principal['teamId'] or not isinstance(reply.get('teams'),list)
                or len(reply['teams'])>1000):raise ValueError('团队列表与当前账号不匹配')
        seen=set()
        for row in reply['teams']:
            tid=row.get('teamId');name=row.get('teamName')
            if (not isinstance(tid,str) or str(uuid.UUID(tid))!=tid or tid in seen or not isinstance(name,str)
                    or not 1<=len(name)<=100 or any(ord(c)<32 for c in name) or type(row.get('active')) is not bool
                    or row.get('role') not in ('admin','member') or row.get('membership') not in ('joined','pending','unjoined','rejected')):
                raise ValueError('团队列表无效')
            seen.add(tid)
        return reply['teams']

    def switch_team(self,team):
        if not self.principal or self.principal.get('multiTeamProtocol')!=1:raise ValueError('服务尚不支持多团队')
        target=str(uuid.UUID(team));account=self.principal['accountId'];old=self.token
        reply=self.request('POST','/v1/me/teams/switch',dict(teamId=target))
        try:
            principal=reply['principal'];token=reply['token']
            if (principal['accountId']!=account or principal['teamId']!=target or principal.get('multiTeamProtocol')!=1
                    or not isinstance(token,str) or token==old or not 32<=len(token)<=128):raise ValueError('切换后的团队身份不匹配')
            self.principal,self.token=principal,token
        except (KeyError,TypeError,ValueError):
            self.principal,self.token=None,'';raise ValueError('团队切换回执无效，请重新登录') from None
        return self.principal

    def logout(self):
        try:
            if self.token:
                self.request('POST', '/v1/auth/logout', {})
        finally:
            self.token, self.principal = '', None


@dataclass(frozen=True)
class PublishPart:
    score_id: int
    sources: tuple[Path, ...]
    title: str
    part_name: str
    number: str
    category: str
    digest: str
    size: int
    accounts: tuple[str, ...]
    upload: UploadPdf | None = None

    @property
    def effective_path(self):
        return self.upload.path if self.upload else self.sources[0]

    @property
    def effective_digest(self):
        return self.upload.digest if self.upload else self.digest

    @property
    def effective_size(self):
        return self.upload.size if self.upload else self.size


@dataclass(frozen=True)
class PublishPlan:
    parts: tuple[PublishPart, ...]
    skipped: tuple[UploadIssue, ...] = ()
    prepared: bool = False


def publication_batches(parts):
    """No queue count limit; each request stays within server limits."""
    empty_id = str(uuid.UUID(int=0))
    batches, batch = [], []
    def fits(rows):
        body = dict(requestId=empty_id, assignments=[dict(artifactId=empty_id, accountIds=p.accounts) for p in rows])
        return len(rows) <= MAX_PUBLICATION_ITEMS and len(json.dumps(body).encode('utf-8')) <= MAX_REQUEST_BYTES
    for part in parts:
        if not fits([part]):
            raise ValueError('单份分谱的接收清单超过服务容量，请减少该份接收成员')
        if batch and not fits([*batch, part]):
            batches.append(tuple(batch))
            batch = []
        batch.append(part)
    if batch:
        batches.append(tuple(batch))
    return tuple(batches)


class PublishService:
    _numbering_version = 1
    _api_prefix = '/v1'

    def __init__(self, db):
        self.db = db
        root = db.get_setting('output_root', '')
        self.root = Path(root).absolute() if root else None
        self.pdf_workspace = UploadPdfWorkspace()

    def close(self):
        self.pdf_workspace.close()

    def _require_runtime(self):
        pass

    def _archive(self):
        return ArchiveService(self.db, self.root)

    def _remote_requirement(self, plan):
        return tuple(part.number for part in plan.parts)

    def _publication_prefix(self, client):
        if self._api_prefix == '/v2':
            return self._api_prefix
        protocol = client.principal.get('numberingProtocol', 1)
        if type(protocol) is not int or protocol not in (1, 2):
            raise ValueError('服务发布协议不兼容，请重新登录或升级整理器；未继续上传')
        # Transport follows the authenticated server; local numbering is retained.
        return '/v2' if protocol == 2 else '/v1'

    def _before_remote_write(self, plan, client, scope, requirement):
        if self._publication_prefix(client) == '/v2':
            from score_organizer.domain.publication_capabilities import require_expanded_publication
            reply = client.request('GET', '/v2/admin/capabilities')
            require_expanded_publication(reply, client.principal['teamId'], requirement)
        self.verify(plan)

    def build_plan(self, assignments):
        self._require_runtime()
        if not self.root or not self.root.is_dir():
            raise ValueError('请先设置并建立乐谱库目录')
        if not assignments:
            raise ValueError('请至少选择一份乐谱 PDF')
        rows = {int(r['id']): r for r in self.db.list_archived_scores()}
        categories = {r['code']: r['name'] for r in self.db.list_categories()}
        archive, parts = self._archive(), []
        paths = archive.score_paths_for_rows(rows.values(), {int(key) for key in assignments})
        for score_id, recipients in sorted(assignments.items()):
            parts.append(self._plan_part(score_id, recipients, rows, categories, paths))
        publication_batches(parts)
        return PublishPlan(tuple(parts))

    def _plan_part(self, score_id, recipients, rows, categories, paths):
        score = rows.get(int(score_id))
        if score is None:
            raise ValueError('所选分谱已变化，请重新选择')
        accounts = tuple(sorted({str(uuid.UUID(a)) for a in recipients}))
        if not 1 <= len(accounts) <= 100:
            raise ValueError('请为每份分谱明确选择 1～100 位接收成员')
        sources = paths[int(score_id)]
        for source in sources:
            reject_links(source)
            if not source.resolve().is_relative_to(self.root.resolve()) or not source.is_file() or sha256_file(source) != score['source_sha256']:
                raise ValueError(f"库内副本缺失或变化，请先修复：{score['output_name']}")
        size = sources[0].stat().st_size
        if not 0 < size <= MAX_PDF:
            raise PdfInputError('每份 PDF 不能超过 100 MiB')
        number = format_score_number(score['instrument_code'], score['category_code'], score['seq'],
                                     naming_version=self._numbering_version)
        return PublishPart(int(score_id), sources, score['display_title'],
                           f"{score['instrument_code']} {score['instrument_name']}", number,
                           f"{score['category_code']} {categories[score['category_code']]}", score['source_sha256'], size, accounts)

    def verify(self, plan):
        current = self.build_plan({p.score_id:p.accounts for p in plan.parts})
        if current.parts != tuple(replace(part, upload=None) for part in plan.parts):
            raise ValueError('预览后曲谱信息或文件发生变化，请重新预览')
        for part in plan.parts:
            if part.upload:
                reject_links(part.upload.path)
                if (part.upload.path.stat().st_size != part.upload.size
                        or sha256_file(part.upload.path) != part.upload.digest):
                    raise ValueError('上传兼容副本已变化，请重新预览')

    def prepare_plan(self, assignments, progress=lambda message: None):
        self._require_runtime()
        if not self.root or not self.root.is_dir():
            raise ValueError('请先设置并建立乐谱库目录')
        if not assignments:
            raise ValueError('请至少选择一份乐谱 PDF')
        rows = {int(row['id']): row for row in self.db.list_archived_scores()}
        categories = {row['code']: row['name'] for row in self.db.list_categories()}
        archive = self._archive()
        paths = archive.score_paths_for_rows(rows.values(), {int(key) for key in assignments})
        parts, skipped = [], []
        for index, (score_id, recipients) in enumerate(sorted(assignments.items()), 1):
            row = rows.get(int(score_id))
            title = row['display_title'] if row else '已变化的分谱'
            number = (format_score_number(row['instrument_code'], row['category_code'], row['seq'],
                      naming_version=self._numbering_version) if row else str(score_id))
            progress(f'检查 PDF {index}/{len(assignments)}：{number} {title}')
            try:
                part = self._plan_part(score_id, recipients, rows, categories, paths)
                for label in (part.title, part.category, part.part_name):
                    if not 1 <= len(label.strip()) <= 100 or any(ord(char) < 32 for char in label):
                        raise PdfInputError('曲名、分类或谱种文字超过服务范围，请先校对')
                upload = self.pdf_workspace.prepare(part.sources[0], part.digest)
                parts.append(replace(part, upload=upload))
            except PdfInputError as error:
                skipped.append(UploadIssue(int(score_id), number, title, str(error)))
        publication_batches(parts)
        return PublishPlan(tuple(parts), tuple(skipped), True)

    def publish(self, plan, client, publisher_id, *, confirmed=False, progress=lambda message: None):
        if not confirmed:
            raise ValueError('请预览并确认每份乐谱 PDF 及整份文件的接收成员')
        progress('正在重新核对预览、原件及上传副本…')
        self.verify(plan)
        if not client.principal or client.principal['role'] != 'admin':
            raise ValueError('请先以管理员账号登录')
        namespace = uuid.UUID(publisher_id)
        # Account+team+origin prevent receipts crossing accounts, teams or servers.
        scope = json.dumps([client.address, client.principal['teamId'], client.principal['accountId']])
        api_prefix = self._publication_prefix(client)
        batches = publication_batches(plan.parts)
        requirement = self._remote_requirement(plan)
        def check_scope():
            principal = client.principal
            if (not principal or principal.get('role') != 'admin'
                    or json.dumps([client.address, principal.get('teamId'), principal.get('accountId')]) != scope
                    or self._publication_prefix(client) != api_prefix):
                raise ValueError('发布期间账号、乐团、服务地址或协议发生变化，请重新登录预览')

        def before_write(affected):
            check_scope()
            self._before_remote_write(affected, client, scope, requirement)
            check_scope()

        receipts, artifacts = [], []
        skipped = list(plan.skipped)
        processed = 0
        try:
            for batch_index, batch in enumerate(batches, 1):
                staged, staged_parts = [], []
                for part in batch:
                    processed += 1
                    index = processed
                    progress(f'上传 {index}/{len(plan.parts)}（第 {batch_index}/{len(batches)} 批）：{part.number} {part.title}')
                    spec = dict(title=part.title, partName=part.part_name, scoreNumber=part.number, category=part.category,
                                sha256=part.effective_digest, size=part.effective_size, independentSource=True)
                    key = scope + json.dumps([spec, part.accounts], sort_keys=True, ensure_ascii=False)
                    spec['requestId'] = str(uuid.uuid5(namespace, key))
                    try:
                        before_write(PublishPlan((part,)))
                        reply = client.request('POST', api_prefix + '/admin/uploads', spec)
                        uid = str(uuid.UUID(reply['uploadId']))
                        if not reply['complete']:
                            before_write(PublishPlan((part,)))
                            result = client.request('POST', f'{api_prefix}/admin/uploads/{uid}/content', file=part.effective_path, size=part.effective_size)
                            if result['artifactId'] != uid or result['hash'] != part.effective_digest or result['size'] != part.effective_size:
                                raise ValueError('服务器文件校验与预览不一致，未提交本批')
                    except TeamRequestError as error:
                        if not plan.prepared or error.status not in (400, 413, 415):
                            raise
                        skipped.append(UploadIssue(part.score_id, part.number, part.title, str(error)))
                        progress(f'本份跳过：{part.number} {part.title}；{error}')
                        continue
                    staged.append(dict(artifactId=uid, accountIds=list(part.accounts)))
                    staged_parts.append(part)
                if not staged:
                    continue
                progress(f'核对并提交第 {batch_index}/{len(batches)} 批；已确认 {len(artifacts)}/{len(plan.parts)} 份…')
                request_id = str(uuid.uuid5(namespace, scope + json.dumps(staged, sort_keys=True)))
                before_write(PublishPlan(tuple(staged_parts)))
                receipt = client.request('POST', api_prefix + '/admin/publications', dict(requestId=request_id, assignments=staged))
                expected = [item['artifactId'] for item in staged]
                if receipt.get('ok') is not True or receipt.get('publicationId') != request_id or receipt.get('artifactIds') != expected:
                    raise ValueError('发布回执不匹配，请保留当前方案重试核验')
                receipts.append(receipt)
                artifacts.extend(expected)
                progress(f'已确认 {len(artifacts)}/{len(plan.parts)} 份（{batch_index}/{len(batches)} 批）')
        except Exception as exc:
            if artifacts:
                raise ValueError(f'已确认 {len(artifacts)}/{len(plan.parts)} 份，剩余尚未确认。保留当前方案重试会核验已有回执，不会重复创建。原因：{exc}') from exc
            raise
        if plan.prepared:
            return dict(ok=True, artifactIds=artifacts, publicationId=receipts[-1]['publicationId'] if receipts else None,
                        publicationIds=[row['publicationId'] for row in receipts], skipped=tuple(skipped))
        if len(receipts) == 1:
            return receipts[0]
        return dict(ok=True, artifactIds=artifacts, publicationId=receipts[-1]['publicationId'],
                    publicationIds=[row['publicationId'] for row in receipts])
