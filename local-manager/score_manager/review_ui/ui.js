'use strict';
const $ = id => document.getElementById(id), launch = location.hash.slice(1);
history.replaceState(null, '', location.pathname);
let reviewed = null, savedReceipt = null, phase = 'CONNECTING', busy = false;
let epoch = 0, objectURL = null;
const labels = {CONNECTING:'CONNECTING · 连接中', DRAFT:'DRAFT · 未提交', CONFIRMING:'CONFIRMING · 正在确认', CANCELLING:'CANCELLING · 正在取消', RECOVERING:'RECOVERING · 核对权威状态', UNKNOWN:'UNKNOWN · 结果未知', COMMITTED:'COMMITTED · 已保存', CANCELLED:'CANCELLED · 已取消'};
function node(tag, text, cls) { const n=document.createElement(tag); if(text!==undefined)n.textContent=text; if(cls)n.className=cls; return n; }
function message(text, error=false) { $('message').textContent=text; $('message').classList.toggle('error',error); }
function controls() {
  const editable=phase==='DRAFT' && !busy;
  $('state').textContent=labels[phase];
  $('member').disabled=$('refresh').disabled=$('cancel').disabled=!editable;
  $('ack').disabled=!editable || !reviewed;
  $('save').disabled=busy || !(phase==='COMMITTED' && savedReceipt || editable && reviewed && $('ack').checked);
  $('save').textContent=savedReceipt?'重试确认 · 读取原回执':'确认保存到正式库';
  $('recover').hidden=phase!=='UNKNOWN'; $('recover').disabled=busy;
  document.querySelectorAll('.page').forEach(b=>b.disabled=!editable);
}
function clearImage() {
  if(objectURL)URL.revokeObjectURL(objectURL); objectURL=null;
  $('paper').replaceChildren(node('p','选择一个获准声部的源页','empty'));
  $('page-label').textContent='选择一个获准声部的源页';
}
function clearReview() {
  epoch++; reviewed=null; $('ack').checked=false;
  $('parts').replaceChildren(); $('summary').replaceChildren();
  $('counts').textContent='等待重新核验'; clearImage(); controls();
}
function terminal(status) {
  // Terminal states are monotonic. No delayed preview or status can undo them.
  if(phase==='COMMITTED' || phase==='CANCELLED')return;
  epoch++; reviewed=null; phase=status.state; busy=false; clearImage();
  if(phase==='COMMITTED') {
    savedReceipt=status;
    $('member').value=status.reviewedMemberId;
    if(!$('summary').childElementCount)$('counts').textContent='已提交 · 权威回执已恢复';
    $('receipt').textContent=`回执 ${status.draftId}\n摘要 ${status.confirmationHash}`;
    $('parts').replaceChildren(node('p','草稿已保存，预览会话结束。重复确认只读取同一回执。','muted'));
    message('已原子保存到正式库。已核对权威回执，再次确认不会重复导入。');
  } else {
    $('parts').replaceChildren(); $('summary').replaceChildren(); $('counts').textContent='草稿已取消';
    message('草稿已取消，正式资产未改变。');
  }
  controls();
}
async function api(route, args={}, image=false) {
  const r=await fetch('/api/'+route,{method:'POST',headers:{'Content-Type':'application/json','X-Local-Review':launch},body:JSON.stringify(args),cache:'no-store'});
  if(!r.ok){const e=await r.json();throw Error(e.error||'本地请求失败');}
  return image?r.blob():r.json();
}
async function recover(reason='', autoReview=false) {
  if(phase==='CANCELLED')return;
  if(savedReceipt){busy=false;controls();message('已保存的权威回执仍有效；可重试读取原回执。');return;}
  const current=++epoch; phase='RECOVERING';busy=true;clearImage();controls();
  try {
    const data=await api('status'); if(current!==epoch)return;
    const selected=$('member').value; $('member').replaceChildren();
    for(const m of data.members){const option=node('option',m.name);option.value=m.id;$('member').append(option);}
    if(data.members.some(m=>m.id===selected))$('member').value=selected;
    if(data.status.state==='COMMITTED' || data.status.state==='CANCELLED'){terminal(data.status);return;}
    if(data.status.state!=='DRAFT')throw Error('无法辨认权威状态');
    phase='DRAFT';busy=false;clearReview();
    message(reason || '已核对正式库状态，请重新核验草稿。',!!reason);
    if(autoReview)await refresh();
  } catch(e) {
    if(current!==epoch)return;
    phase='UNKNOWN';busy=false;clearReview();
    message('结果未知：暂时无法取得权威状态。请重新核对状态；不要把请求失败当作未提交。',true);
  }
}
const actionNames={import_pdf:'导入 PDF',import_pdfs:'批量导入 PDF',add_category:'新增分类',add_instrument:'新增乐器',add_member:'新增成员',add_piece:'新增曲目',assign_number:'调整编号',add_part:'新增声部',add_movement:'新增乐章',bind:'绑定源页',import_dictionary_csv:'导入字典',restore_archive:'恢复开放包'};
function detail(title,value){const d=node('details');d.append(node('summary',title),node('pre',JSON.stringify(value,null,2)));return d;}
function renderReview(r,member) {
  const s=r.administratorSummary;
  $('counts').textContent=`${s.operations.length} 项操作 · ${s.assetChanges.length} 项资产差异 · ${s.originalFileChanges.length} 项文件差异`;
  s.operations.forEach((op,i)=>$('summary').append(detail(`${i+1}. ${actionNames[op.action]||op.action} · ${op.requestId}`,op)));
  $('summary').append(detail('全部资产前后差异（完整字段）',s.assetChanges),detail('全部原 PDF 文件与页几何差异',s.originalFileChanges));
  if(!r.memberPreview.pieces.length)$('parts').append(node('p','该成员没有获准声部。管理员角色不绕过授权。','empty'));
  for(const p of r.memberPreview.pieces) {
    const box=node('div',undefined,'piece');box.append(node('div',`${p.number??'未编号'} / ${p.title}`,'piece-title'));
    const playing=p.playing.partId?p.visibleParts.find(v=>v.partId===p.playing.partId)?.name:p.playing.error;
    box.append(node('div',`演奏选择：${playing}`,'playing'));
    for(const v of p.visibleParts) {
      const part=node('div',`可查看：${v.name}`,'part');if(v.error)part.append(node('p',v.error,'error'));
      for(const segment of v.segments||[]) {
        const pages=node('div',undefined,'pages');
        for(const page of segment.sourcePages){const b=node('button',`源第 ${page+1} 页`,'page');b.addEventListener('click',()=>showPage(r,member,v,segment,page,b));pages.append(b);}
        part.append(pages);
      }
      box.append(part);
    }
    $('parts').append(box);
  }
}
async function refresh() {
  if(phase!=='DRAFT' || busy)return;
  clearReview();const current=epoch,member=$('member').value;if(!member)return;
  message('正在核验来源、PDF 内容与草稿版本…');
  try {
    const r=await api('review',{member});if(current!==epoch || phase!=='DRAFT' || busy)return;
    reviewed=r;renderReview(r,member);controls();message('草稿核验完成。可查看完整变更、切换成员并预览获准页。');
  } catch(e) { if(current===epoch && phase==='DRAFT' && !busy){clearReview();message(e.message,true);} }
}
async function showPage(r,member,part,segment,page,button) {
  if(phase!=='DRAFT' || busy || reviewed!==r)return;
  const current=++epoch;clearImage();message('正在重新核验并渲染获准源页…');
  try {
    const blob=await api('page',{member,hash:r.confirmationHash,part:part.partId,binding:segment.bindingId,page},true);
    if(current!==epoch || phase!=='DRAFT' || busy)return;
    objectURL=URL.createObjectURL(blob);const img=node('img');img.src=objectURL;img.alt=`${part.name} · 源第 ${page+1} 页`;
    $('paper').replaceChildren(img);$('page-label').textContent=`${part.name} · 源第 ${page+1} 页 · 版本 ${segment.sourceVersionId}`;
    document.querySelectorAll('.page').forEach(b=>b.classList.remove('selected'));button.classList.add('selected');
    message('仅显示该获准页的图像。未向浏览器发送母 PDF。');
  } catch(e) { if(current===epoch && phase==='DRAFT' && !busy){clearReview();message(e.message,true);} }
}
$('member').addEventListener('change',refresh);$('refresh').addEventListener('click',refresh);
$('ack').addEventListener('change',controls);$('recover').addEventListener('click',()=>recover('',true));
$('save').addEventListener('click',async()=>{
  if(busy)return;
  const prior=savedReceipt;
  if(!prior && (phase!=='DRAFT' || !reviewed || !$('ack').checked))return;
  const args=prior?{member:prior.reviewedMemberId,hash:prior.confirmationHash,confirm:true}:{member:reviewed.memberPreview.memberId,hash:reviewed.confirmationHash,confirm:true};
  epoch++;busy=true;if(!prior)phase='CONFIRMING';clearImage();controls();
  try {
    const result=await api('confirm',args);
    if(prior){busy=false;controls();message('已原子保存到正式库。已读取同一权威回执，不会重复导入。');}
    else terminal(result);
  } catch(e) {
    if(prior){busy=false;controls();message('回执重试暂未完成；已有已提交回执仍有效。',true);}
    else await recover(e.message);
  }
});
$('cancel').addEventListener('click',async()=>{
  if(phase!=='DRAFT' || busy || !window.confirm('取消本草稿？正式库不会改变。'))return;
  phase='CANCELLING';busy=true;clearReview();controls();
  try{terminal(await api('cancel',{confirm:true}));}catch(e){await recover(e.message);}
});
controls();
if(!launch){phase='UNKNOWN';controls();message('请使用本机启动命令给出的完整会话地址打开。',true);}
else recover('',true);
