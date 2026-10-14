"""Deterministic browser fault injection: real backend commit, held/lost responses."""
import argparse
import asyncio
import json
from pathlib import Path
from threading import Thread
from playwright.async_api import async_playwright
from review_fixture import prepare
from score_manager import Catalog
from score_manager.local_review import LocalServer, Review
from score_manager.workflow import Workflow, digest


async def run(root, baseline=False):
    root.mkdir(parents=True,exist_ok=False)
    results=[]
    async with async_playwright() as pw:
        browser=await pw.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox'])
        for case in ['lost_confirm','late_review_after_cancel','late_page_after_confirm'] + ([] if baseline else ['unknown_then_recover','lost_cancel','inflight_page_error']):
            f=prepare(root/case);w=Workflow(f['live'])
            server=LocalServer(Review(w,f['draft'],'/usr/bin/pdftocairo'))
            thread=Thread(target=server.serve_forever,daemon=True);thread.start()
            page=await browser.new_page(viewport={'width':1400,'height':1100})
            page.set_default_timeout(60000)
            url=server.origin+'/#'+server.token
            async def ready():
                await page.goto(url)
                await page.wait_for_function("() => document.querySelector('#counts').textContent.includes('项操作')")
                await page.select_option('#member','legacy-player')
                await page.wait_for_function("() => document.querySelector('#parts').textContent.includes('合成练习')")
            def state():
                with Catalog(f['live']) as c:return digest(c)
            before=state();gates=[]
            try:
                await ready()
                if case in ['lost_confirm','unknown_then_recover']:
                    committed=asyncio.Event()
                    async def lose(route):
                        response=await route.fetch();assert response.status==200
                        committed.set();await route.abort('connectionreset')
                    await page.route('**/api/confirm',lose)
                    if case=='unknown_then_recover':
                        await page.route('**/api/status',lambda route:route.abort('connectionreset'))
                    await page.check('#ack');await page.click('#save');await asyncio.wait_for(committed.wait(),60)
                    await page.wait_for_timeout(700)
                    if case=='unknown_then_recover':
                        assert 'UNKNOWN' in await page.locator('#state').inner_text()
                        assert await page.locator('#cancel').is_disabled() and await page.locator('#member').is_disabled()
                        await page.screenshot(path=str(root/(case+'-unknown.png')),full_page=True)
                        await page.unroute('**/api/status');await page.click('#recover')
                    await page.wait_for_timeout(500)
                    assert 'COMMITTED' in await page.locator('#state').inner_text(), 'server committed but frontend did not recover authoritative receipt'
                    assert f['draft'] in await page.locator('#receipt').inner_text()
                    assert await page.locator('#cancel').is_disabled()
                    after=state();await page.unroute('**/api/confirm')
                    await page.click('#save');await page.wait_for_timeout(700);assert state()==after
                    await page.close();page=await browser.new_page(viewport={'width':1400,'height':1100});await page.goto(url)
                    await page.wait_for_function("() => document.querySelector('#receipt').textContent.includes('回执')")
                    assert 'COMMITTED' in await page.locator('#state').inner_text()
                    await page.click('#save');await page.wait_for_timeout(700);assert state()==after
                    with Catalog(f['live']) as c:assert c._db.execute('SELECT count(*) FROM local_workflow_receipts').fetchone()[0]==1
                elif case=='late_review_after_cancel':
                    cancel_entered=asyncio.Event();release_cancel=asyncio.Event();gates.append(release_cancel)
                    review_entered=asyncio.Event();release_review=asyncio.Event();gates.append(release_review)
                    async def hold_cancel(route):
                        cancel_entered.set();await release_cancel.wait();await route.fulfill(response=await route.fetch())
                    async def hold_review(route):
                        response=await route.fetch();review_entered.set();await release_review.wait();await route.fulfill(response=response)
                    await page.route('**/api/cancel',hold_cancel);await page.route('**/api/review',hold_review)
                    page.once('dialog',lambda d:d.accept());await page.click('#cancel');await asyncio.wait_for(cancel_entered.wait(),60)
                    unlocked=await page.locator('#member').is_enabled() or await page.locator('#refresh').is_enabled()
                    if unlocked:
                        await page.select_option('#member','legacy-admin');await asyncio.wait_for(review_entered.wait(),60)
                    else:
                        # Even a synthetic event must not launch a review during cancellation.
                        await page.evaluate("document.querySelector('#member').dispatchEvent(new Event('change'))")
                    release_cancel.set();await page.wait_for_function("() => document.querySelector('#state').textContent.includes('CANCELLED')")
                    release_review.set();await page.wait_for_timeout(500)
                    assert 'CANCELLED' in await page.locator('#state').inner_text(), 'late review repainted cancelled draft as DRAFT'
                    assert not unlocked,'member/review controls remained enabled while cancelling'
                    assert state()==before
                elif case=='late_page_after_confirm':
                    done=asyncio.Event();release=asyncio.Event();gates.append(release)
                    page_done=asyncio.Event();release_page=asyncio.Event();gates.append(release_page)
                    async def hold_confirm(route):
                        response=await route.fetch();assert response.status==200;done.set();await release.wait();await route.fulfill(response=response)
                    async def hold_page(route):
                        response=await route.fetch();assert response.status==409;page_done.set();await release_page.wait();await route.fulfill(response=response)
                    await page.route('**/api/confirm',hold_confirm);await page.route('**/api/page',hold_page)
                    await page.check('#ack');await page.click('#save');await asyncio.wait_for(done.wait(),60)
                    button=page.locator('.piece').filter(has_text='合成练习').get_by_role('button',name='源第 2 页')
                    unlocked=await button.is_enabled()
                    if unlocked:
                        await button.click();await asyncio.wait_for(page_done.wait(),60)
                    else:await button.dispatch_event('click')
                    release.set();await page.wait_for_function("() => document.querySelector('#state').textContent.includes('COMMITTED')")
                    release_page.set();await page.wait_for_timeout(500)
                    assert await page.locator('#save').is_enabled(), 'late page error removed committed receipt retry state'
                    assert '已原子保存' in await page.locator('#message').inner_text(), 'late page error replaced success message'
                    assert not unlocked,'page buttons remained enabled during confirmation'
                elif case=='inflight_page_error':
                    entered=asyncio.Event();release=asyncio.Event();gates.append(release)
                    async def fail_late(route):
                        response=await route.fetch();assert response.status==200
                        entered.set();await release.wait()
                        await route.fulfill(status=409,content_type='application/json',body='{"error":"Delayed page failure"}')
                    await page.route('**/api/page',fail_late);await page.check('#ack')
                    await page.locator('.piece').filter(has_text='合成练习').get_by_role('button',name='源第 2 页').click()
                    await asyncio.wait_for(entered.wait(),60);await page.click('#save')
                    await page.wait_for_function("() => document.querySelector('#state').textContent.includes('COMMITTED')")
                    release.set();await page.wait_for_timeout(500)
                    assert '已原子保存' in await page.locator('#message').inner_text()
                    assert await page.locator('#save').is_enabled() and f['draft'] in await page.locator('#receipt').inner_text()
                else:
                    async def lose_cancel(route):
                        response=await route.fetch();assert response.status==200;await route.abort('connectionreset')
                    await page.route('**/api/cancel',lose_cancel);page.once('dialog',lambda d:d.accept());await page.click('#cancel')
                    await page.wait_for_function("() => document.querySelector('#state').textContent.includes('CANCELLED')")
                    assert state()==before and await page.locator('#member').is_disabled()
                await page.screenshot(path=str(root/(case+'.png')),full_page=True)
                results.append({'case':case,'passed':True,'authority':w.status(f['draft'])['state']})
            except Exception as error:
                results.append({'case':case,'passed':False,'error':str(error),'authority':w.status(f['draft'])['state'],
                                'frontendState':await page.locator('#state').inner_text(),'message':await page.locator('#message').inner_text()})
                await page.screenshot(path=str(root/(case+'-failure.png')),full_page=True)
            finally:
                for gate in gates:gate.set()
                await page.unroute_all(behavior='ignoreErrors')
                await page.close();server.shutdown();thread.join();server.server_close()
        await browser.close()
    report={'baseline':baseline,'cases':results,'passed':all(r['passed'] for r in results),'syntheticOnly':True}
    (root/'race-result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps(report,ensure_ascii=False))
    if baseline:
        expected=['server committed but frontend did not recover authoritative receipt','late review repainted cancelled draft as DRAFT','late page error removed committed receipt retry state']
        assert [r.get('error') for r in results]==expected, 'Each race must fail at its actual state assertion, not a harness timeout'
    else:assert report['passed']


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('output',type=Path);p.add_argument('--baseline',action='store_true');a=p.parse_args()
    asyncio.run(run(a.output,a.baseline))
