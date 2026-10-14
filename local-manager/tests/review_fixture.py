"""Distinct synthetic PDF pages for actual rendering and visual review; no music source."""
import io
from pathlib import Path
from pypdf import PdfWriter
from pypdf.generic import DecodedStreamObject, DictionaryObject, NameObject
from fixtures import legacy_archive
from score_manager import Catalog
from score_manager.archive import restore_archive
from score_manager.validation import sha
from score_manager.workflow import Workflow


def prepare(root):
    root.mkdir(parents=True, exist_ok=False)
    source = root / 'synthetic-staff.pdf'
    writer = PdfWriter()
    font = writer._add_object(DictionaryObject({NameObject('/Type'):NameObject('/Font'),NameObject('/Subtype'):NameObject('/Type1'),NameObject('/BaseFont'):NameObject('/Helvetica')}))
    for n in range(5):
        page = writer.add_blank_page(width=600, height=780)
        page[NameObject('/Resources')] = DictionaryObject({NameObject('/Font'):DictionaryObject({NameObject('/F1'):font})})
        drawing = [f'BT /F1 22 Tf 45 720 Td (SYNTHETIC REVIEW / PAGE {n+1}) Tj ET',
                   'BT /F1 11 Tf 45 690 Td (Generated test marks - not a private or published score) Tj ET']
        for y in [550,420,290]:
            for j in range(5): drawing.append(f'0.3 w 45 {y+j*9} m 550 {y+j*9} l S')
            for k in range(7):
                x=80+k*65; offset=(k+n)%5*9
                drawing.append(f'{x} {y+offset} 8 5 re f 1 w {x+8} {y+offset} m {x+8} {y+offset+33} l S')
        drawing.append(f'BT /F1 12 Tf 45 80 Td (Original page index: {n}. Preview must match authorized binding.) Tj ET')
        stream=DecodedStreamObject();stream.set_data(('\n'.join(drawing)+'\n').encode());page[NameObject('/Contents')]=writer._add_object(stream)
    buf=io.BytesIO();writer.write(buf);source.write_bytes(buf.getvalue())
    legacy = root / 'synthetic-legacy.zip';legacy_archive(legacy)
    live=root/'live.sqlite'
    with Catalog(live,create=True) as c:
        restore_archive(c,legacy,expected_sha256=sha(legacy.read_bytes()))
        c.assign_number('legacy-piece',number=998)
    w=Workflow(live);draft=w.start()['draftId']
    def edit(action,**args):return w.edit(draft,action,args,request_id=action)['result']
    pdf=edit('import_pdf',path=str(source))
    category=edit('add_category',code='73',name='合成样本 / 用户分类')
    piece=edit('add_piece',title='合成练习 · 可视复核',category_id=category)
    part=edit('add_part',piece_id=piece,instrument_id='legacy-instrument')
    binding=edit('bind',part_id=part,pdf_id=pdf['scorePdfId'],start_page=2,end_page=3)
    return {'live':live,'source':source,'draft':draft,'piece':piece,'part':part,'binding':binding,'version':pdf['versionId']}
