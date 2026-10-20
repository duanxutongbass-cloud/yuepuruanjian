"""Prepare traceable upload copies without modifying archived PDFs."""
from dataclasses import dataclass
import hashlib
import math
from pathlib import Path
import tempfile

from pypdf import PdfReader, PdfWriter
from pypdf.errors import PyPdfError
from pypdf.generic import NameObject, RectangleObject

from score_organizer.services.import_service import sha256_file
from score_organizer.services.program_service import reject_links

MAX_PDF = 100 * 1024 * 1024


class PdfInputError(ValueError):
    pass


class UploadIntegrityError(RuntimeError):
    pass


class CopyStorageError(UploadIntegrityError):
    pass


@dataclass(frozen=True)
class UploadPdf:
    path: Path
    digest: str
    size: int
    pages: int
    explanation: str = ''


@dataclass(frozen=True)
class UploadIssue:
    score_id: int
    number: str
    title: str
    reason: str


def inspect_pdf(reader, *, normalized=False):
    if reader.is_encrypted:
        raise PdfInputError('PDF 已加密，请提供无密码的可阅读副本')
    root = reader.trailer['/Root']
    names = root.get('/Names', {})
    if hasattr(names, 'get_object'):
        names = names.get_object()
    if (any(key in root for key in ('/OpenAction', '/AA', '/AcroForm'))
            or any(key in names for key in ('/EmbeddedFiles', '/JavaScript'))):
        raise PdfInputError('PDF 含表单、自动动作或嵌入内容，需另行提供静态阅读副本')
    count = len(reader.pages)
    if not 1 <= count <= 2000:
        raise PdfInputError('PDF 页数不在 NAS 支持的 1～2000 页范围内')
    reverse = False
    for page in reader.pages:
        for box in (page.mediabox, page.cropbox):
            coordinates = tuple(float(value) for value in box)
            if (not all(math.isfinite(value) for value in coordinates)
                    or abs(coordinates[2] - coordinates[0]) == 0
                    or abs(coordinates[3] - coordinates[1]) == 0):
                raise PdfInputError('PDF 页面框为空或含非有限坐标，不能可靠转换')
            backwards = coordinates[2] < coordinates[0] or coordinates[3] < coordinates[1]
            reverse |= backwards
            if normalized and backwards:
                raise PdfInputError('兼容副本的页面框校验未通过')
    return count, reverse


def page_signatures(reader):
    signatures = []
    for page in reader.pages:
        contents = page.get_contents()
        annots = page.get('/Annots', ())
        if hasattr(annots, 'get_object'):
            annots = annots.get_object()
        signatures.append((hashlib.sha256(contents.get_data() if contents is not None else b'').hexdigest(),
                           int(page.get('/Rotate', 0)), float(page.get('/UserUnit', 1)), len(annots)))
    return tuple(signatures)


class UploadPdfWorkspace:
    def __init__(self):
        self._temporary = None
        self._copies = {}

    def close(self):
        if self._temporary is not None:
            self._temporary.cleanup()
            self._temporary = None
        self._copies.clear()

    def prepare(self, source, expected_hash):
        source = Path(source)
        reject_links(source)
        if sha256_file(source) != expected_hash:
            raise UploadIntegrityError('预览期间原文件发生变化，请重新检查')
        size = source.stat().st_size
        if not 0 < size <= MAX_PDF:
            raise PdfInputError('PDF 为空或超过 NAS 的 100 MiB 限制')
        if expected_hash in self._copies:
            result = self._copies[expected_hash]
            reject_links(result.path)
            if sha256_file(result.path) != result.digest:
                raise CopyStorageError('上传兼容副本已变化，请关闭发布窗口后重新预览')
            return result
        target = None
        try:
            with source.open('rb') as stream:
                header = stream.read(1024)
                if b'%PDF-' not in header:
                    raise PdfInputError('文件不是可识别的 PDF')
                stream.seek(0)
                relaxed = False
                try:
                    reader = PdfReader(stream, strict=True)
                    pages, reverse = inspect_pdf(reader)
                except PdfInputError:
                    raise
                except (PyPdfError, ValueError, TypeError, KeyError, EOFError, RecursionError):
                    stream.seek(0)
                    reader = PdfReader(stream, strict=False)
                    pages, reverse = inspect_pdf(reader)
                    relaxed = True
                if header.startswith(b'%PDF-') and not relaxed and not reverse:
                    if sha256_file(source) != expected_hash:
                        raise UploadIntegrityError('预览期间原文件发生变化，请重新检查')
                    return UploadPdf(source, expected_hash, size, pages)
                # Clone the document; never rasterize, trim pages, or edit the original.
                signatures = page_signatures(reader)
                writer = PdfWriter()
                writer.clone_document_from_reader(reader)
                for page in writer.pages:
                    for key in ('/MediaBox', '/CropBox'):
                        if key in page:
                            x0, y0, x1, y1 = (float(v) for v in page[key])
                            page[NameObject(key)] = RectangleObject((min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1)))
                try:
                    if self._temporary is None:
                        self._temporary = tempfile.TemporaryDirectory(prefix='score-organizer-upload-')
                    target = Path(self._temporary.name) / (expected_hash + '.pdf')
                    with target.open('xb') as output:
                        writer.write(output)
                except OSError as error:
                    raise CopyStorageError('上传兼容副本写入失败，请检查临时磁盘空间和权限') from error
                finally:
                    writer.close()
        except (PdfInputError, UploadIntegrityError):
            if target is not None:
                target.unlink(missing_ok=True)
            raise
        except OSError as error:
            if target is not None:
                target.unlink(missing_ok=True)
            raise CopyStorageError('无法读取此 PDF，请检查文件权限和磁盘') from error
        except Exception as error:
            if target is not None:
                target.unlink(missing_ok=True)
            raise PdfInputError('PDF 结构无法可靠读取，请用 PDF 工具另存后重试') from error
        try:
            if not 0 < target.stat().st_size <= MAX_PDF:
                raise PdfInputError('兼容副本超过 NAS 的 100 MiB 限制')
            with target.open('rb') as stream:
                rebuilt = PdfReader(stream, strict=True)
                final_pages, _ = inspect_pdf(rebuilt, normalized=True)
                if final_pages != pages:
                    raise PdfInputError('兼容转换前后页数不一致，未采用副本')
                if page_signatures(rebuilt) != signatures:
                    raise PdfInputError('兼容转换改变了页面内容、旋转或批注数量，未采用副本')
            if sha256_file(source) != expected_hash:
                raise UploadIntegrityError('预览期间原文件发生变化，请重新检查')
            reasons = []
            if reverse:
                reasons.append('规范反向页面框')
            if relaxed or not header.startswith(b'%PDF-'):
                reasons.append('重新封装 PDF 结构')
            result = UploadPdf(target, sha256_file(target), target.stat().st_size, pages, '；'.join(reasons))
            self._copies[expected_hash] = result
            return result
        except Exception as error:
            target.unlink(missing_ok=True)
            if isinstance(error, (PdfInputError, UploadIntegrityError)):
                raise
            if isinstance(error, OSError):
                raise CopyStorageError('无法核验上传兼容副本，请检查临时磁盘') from error
            raise PdfInputError('重建后的 PDF 未通过严格校验，未采用副本') from error


def upload_report(plan, receipt=None, error=None):
    compatible = sum(bool(part.upload and part.upload.explanation) for part in plan.parts)
    issues = tuple(receipt.get('skipped', ())) if receipt else plan.skipped
    lines = [f'所选 {len(plan.parts) + len(plan.skipped)} 份；可上传 {len(plan.parts)} 份；兼容副本 {compatible} 份；跳过 {len(issues)} 份。',
             '本地原件及正式曲库未改动。兼容副本按整份 PDF 上传，保留原编号、谱种和接收范围。']
    if receipt is not None:
        lines.append(f"服务器已确认 {len(receipt['artifactIds'])} 份；回执：{receipt.get('publicationIds', [])}。")
    if error:
        lines.append('本次停止原因：' + error)
    for issue in issues:
        lines.append(f'跳过：{issue.number} {issue.title}：{issue.reason}')
    for part in plan.parts:
        upload = part.upload
        lines.append(f'{part.number} {part.title} / {part.part_name}：' +
                     (f'兼容副本（{upload.explanation}）' if upload and upload.explanation else '原件直传'))
        lines.append(f'  原文件 SHA-256：{part.digest}')
        lines.append(f'  上传 SHA-256：{part.effective_digest}')
    return '\n'.join(lines)
