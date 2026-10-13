"""Optional real-browser QA with synthetic PDFs; emits screenshots and assertions."""
import argparse
import json
from pathlib import Path
from threading import Thread
import time

from playwright.sync_api import sync_playwright
from review_fixture import prepare
from score_manager import Catalog
from score_manager.local_review import LocalServer, Review
from score_manager.workflow import Workflow, digest


def run(output):
    output.mkdir(parents=True,exist_ok=False)
    cases=[]
    def state(f):
        with Catalog(f['live']) as c:return digest(c)
    with sync_playwright() as pw:
        browser=pw.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox'])
        page=browser.new_page(viewport={'width':1400,'height':1050},device_scale_factor=1)
        errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
        f=prepare(output/'main-fixture');before=state(f)
        server=LocalServer(Review(Workflow(f['live']),f['draft'],'/usr/bin/pdftocairo'))
        thread=Thread(target=server.serve_forever,daemon=True);thread.start()
        requests=[]
        page.on('response',lambda r:requests.append({'path':r.url.split(server.origin)[-1],'status':r.status,'type':r.headers.get('content-type')}))
        def open_fixture(f):
            server.review=Review(Workflow(f['live']),f['draft'],'/usr/bin/pdftocairo')
            page.goto('about:blank')  # New launch, not a same-document fragment change.
            page.goto(server.origin+'/#'+server.token)
            page.wait_for_function("() => document.querySelector('#state').textContent.includes('DRAFT')")
            page.wait_for_function("() => document.querySelector('#counts').textContent.includes('项操作')")
        try:
            open_fixture(f)
            assert '没有获准声部' in page.locator('#parts').inner_text()
            page.evaluate('window.scrollTo(0,0)')
            page.screenshot(path=str(output/'01-admin-no-permission.png'),full_page=True)
            cases.append('administrator has no implicit member-view permission')
            page.select_option('#member','legacy-player')
            page.wait_for_function("() => document.querySelector('#parts').textContent.includes('合成练习')")
            newpiece=page.locator('.piece').filter(has_text='合成练习')
            assert newpiece.locator('button').all_text_contents()==['源第 2 页','源第 3 页']
            newpiece.get_by_role('button',name='源第 2 页').click()
            page.wait_for_function("() => document.querySelector('#paper img')?.naturalWidth>0")
            page.evaluate('window.scrollTo(0,0)')
            page.screenshot(path=str(output/'02-authorized-source-page2.png'),full_page=True)
            assert state(f)==before
            page.locator('#summary details').last.locator('summary').click()
            assert f['version'] in page.locator('#summary').inner_text()
            page.locator('#summary details').last.locator('summary').click()
            page.locator('#summary details').first.locator('summary').click()
            page.locator('#summary details').nth(5).locator('summary').click()
            page.locator('#summary').screenshot(path=str(output/'06-complete-summary-detail.png'))
            page.locator('#summary details').first.locator('summary').click()
            page.locator('#summary details').nth(5).locator('summary').click()
            cases.append('complete administrator summary and authorized actual PDF source page2; no formal writes')
            page.set_viewport_size({'width':760,'height':1000})
            page.evaluate('window.scrollTo(0,0)')
            page.screenshot(path=str(output/'03-narrow-review.png'),full_page=True)
            assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
            page.set_viewport_size({'width':1400,'height':1050})
            page.select_option('#member','legacy-admin')
            assert page.locator('#paper img').count()==0
            page.wait_for_function("() => document.querySelector('#parts').textContent.includes('没有获准声部')")
            cases.append('member switch immediately clears previously rendered page')
            page.select_option('#member','legacy-player')
            page.wait_for_function("() => document.querySelector('#counts').textContent.includes('项操作')")
            assert page.locator('#save').is_disabled()
            page.check('#ack');page.click('#save')
            page.wait_for_function("() => document.querySelector('#state').textContent.includes('COMMITTED')")
            receipt=page.locator('#receipt').inner_text();saved=state(f)
            page.click('#save');page.wait_for_timeout(800)
            assert receipt==page.locator('#receipt').inner_text() and saved==state(f)
            page.evaluate('window.scrollTo(0,0)')
            page.screenshot(path=str(output/'04-confirmed-receipt.png'),full_page=True)
            cases.append('explicit acknowledgment and repeat confirmation preserve single authoritative receipt')

            cancelled=prepare(output/'cancel-fixture');unchanged=state(cancelled);open_fixture(cancelled)
            page.once('dialog',lambda d:d.accept());page.click('#cancel')
            page.wait_for_function("() => document.querySelector('#state').textContent.includes('CANCELLED')")
            assert state(cancelled)==unchanged
            cases.append('cancel through UI leaves formal assets unchanged')

            stale=prepare(output/'stale-fixture');open_fixture(stale)
            with Catalog(stale['live']) as c:c.assign_number('legacy-piece',number=1001)
            changed=state(stale);page.check('#ack');page.click('#save')
            page.wait_for_function("() => document.querySelector('#message').textContent.includes('STALE_DRAFT')")
            assert state(stale)==changed and page.locator('#save').is_disabled()
            page.evaluate('window.scrollTo(0,0)')
            page.screenshot(path=str(output/'05-stale-data-rejected.png'),full_page=True)
            cases.append('concurrent live numbering rejects old confirmation in UI')

            source=prepare(output/'source-fixture');open_fixture(source)
            page.select_option('#member','legacy-player')
            page.wait_for_function("() => document.querySelector('#parts').textContent.includes('合成练习')")
            source['source'].write_bytes(b'%PDF-changed')
            page.locator('.piece').filter(has_text='合成练习').get_by_role('button',name='源第 2 页').click()
            page.wait_for_function("() => document.querySelector('#message').textContent.includes('Source file changed')")
            assert page.locator('#paper img').count()==0 and page.locator('#save').is_disabled()
            cases.append('changed source PDF invalidates page preview and disables confirmation')
            assert not errors,errors
            assert all(r['type']!='application/pdf' for r in requests)
            report={'passed':True,'cases':cases,'caseCount':len(cases),'pageScriptErrors':errors,'requests':requests,
                    'browserVersion':browser.version,'renderer':'pdftocairo 25.03.0 (installed, not bundled)',
                    'listenAddress':server.server_address[0],'pdfResponses':0,'screenshots':sorted(p.name for p in output.glob('*.png')),
                    'scope':'Actual local administrator browser prototype; synthetic only; no deployment, authentication or member delivery'}
            (output/'browser-result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
            print(json.dumps({k:v for k,v in report.items() if k!='requests'},ensure_ascii=False))
        except Exception:
            (output/'failure-diagnostics.json').write_text(json.dumps({'message':page.locator('#message').inner_text(),'errors':errors,'requests':requests},ensure_ascii=False,indent=2))
            raise
        finally:
            browser.close();server.shutdown();thread.join();server.server_close()
    return report


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('output',type=Path)
    run(parser.parse_args().output)
