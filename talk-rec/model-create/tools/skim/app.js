'use strict';
const $ = id => document.getElementById(id);
const token = document.querySelector('meta[name="skim-token"]').content;
let item, real = null, verdict = null, busy = false, cleanLoaded = false, labelLoaded = false;
async function api(path, data) {
  const response = await fetch('/api/' + path, {method:'POST',headers:{'Content-Type':'application/json','X-Skim-Token':token},body:JSON.stringify(data)});
  const value = await response.json();
  if (!response.ok) throw new Error(value.error || '저장 실패');
  return value;
}
function selected() {
  for (const [group, value] of [['real',real],['verdict',verdict]]) {
    for (const button of $(group).querySelectorAll('button')) button.setAttribute('aria-pressed', String(button.dataset.value === value));
  }
}
async function load(index) {
  if (busy) return;
  busy = true;
  try {
    item = await api('item', {index});
    real = item.decision?.real ?? null; verdict = item.decision?.verdict ?? null;
    $('comment').value = item.decision?.comment ?? '';
    $('transcript').replaceChildren(...item.turns.map(text => {const li = document.createElement('li');li.textContent = text;return li;}));
    $('identity').textContent = item.id + ' · ' + item.seconds.toFixed(1) + '초';
    $('progress').textContent = `${item.index + 1} / ${item.total} · 저장 ${item.done}개`;
    $('clean').hidden = true; $('intended').hidden = true;
    $('clean-lines').replaceChildren(); $('label-text').textContent = ''; $('card').textContent = '';
    $('original').textContent = 'O · 화자 달린 원본 펼치기'; $('label').textContent = 'L · 의도 라벨 표시';
    cleanLoaded = false; labelLoaded = false;
    $('prev').disabled = item.index === 0; $('next').disabled = item.index === item.total - 1;
    $('status').textContent = item.decision ? '저장한 판정 · 다시 선택하면 수정됩니다' : '먼저 전사를 읽고 판단해 주세요';
    selected();
  } catch (error) { $('status').textContent = error.message; }
  finally { busy = false; }
}
async function choose(group, value) {
  if (busy || !item) return;
  if (group === 'real') real = value; else verdict = value;
  selected();
  if (!real || !verdict) return;
  busy = true;
  let next = null;
  try {
    await api('save', {id:item.id, real, verdict, comment:$('comment').value});
    item = await api('item', {index:item.index});
    $('status').textContent = '저장했습니다';
    if (item.index + 1 < item.total) next = item.index + 1;
    else {
      $('status').textContent = item.done === item.total ? '마지막 묶음까지 저장했습니다' : `마지막 묶음 저장 · 미판정 ${item.total - item.done}개`;
      $('progress').textContent = `${item.total} / ${item.total} · 저장 ${item.done}개`;
    }
  } catch (error) { $('status').textContent = error.message; }
  finally { busy = false; }
  if (next !== null) await load(next);
}
async function original() {
  if (busy || !item) return;
  busy = true;
  try {
    if (!cleanLoaded) {
      const data = await api('clean', {id:item.id});
      $('clean-lines').replaceChildren(...data.lines.map(line => {const p = document.createElement('p');p.textContent = `${line.t.toFixed(1)}초 · ${line.spk}${line.overlap ? ' (겹침)' : ''}  ${line.text}`;return p;}));
      cleanLoaded = true;
    }
    $('clean').hidden = !$('clean').hidden;
    $('original').textContent = $('clean').hidden ? 'O · 화자 달린 원본 펼치기' : 'O · 원본 접기';
  } catch (error) { $('status').textContent = error.message; }
  finally { busy = false; }
}
async function label() {
  if (busy || !item) return;
  busy = true;
  try {
    if (!labelLoaded) {
      const data = await api('label', {id:item.id});
      const names = {story:'이야기',moment:'한 줄 순간',none:'없음'};
      $('label-text').textContent = `${names[data.label]} · ${data.description}${data.boundary ? ' · 경계 사례' : ''} / ${data.why}`;
      $('card').textContent = JSON.stringify(data.card, null, 2); labelLoaded = true;
    }
    $('intended').hidden = !$('intended').hidden;
    $('label').textContent = $('intended').hidden ? 'L · 의도 라벨 표시' : 'L · 의도 라벨 가리기';
  } catch (error) { $('status').textContent = error.message; }
  finally { busy = false; }
}
for (const group of ['real','verdict']) for (const button of $(group).querySelectorAll('button')) button.onclick = () => choose(group, button.dataset.value);
$('original').onclick = original; $('label').onclick = label;
$('prev').onclick = () => item && load(Math.max(0,item.index-1));
$('next').onclick = () => item && load(Math.min(item.total-1,item.index+1));
document.addEventListener('keydown', event => {
  if (event.target.matches('textarea,input') || event.ctrlKey || event.altKey || event.metaKey || event.repeat) return;
  const key = event.key.toLowerCase();
  const actions = {y:()=>choose('real','yes'),n:()=>choose('real','no'),m:()=>choose('real','maybe'),
    '1':()=>choose('verdict','story'),'2':()=>choose('verdict','moment'),'3':()=>choose('verdict','none'),
    o:original,l:label,c:()=>$('comment').focus(),j:()=>$('next').click(),k:()=>$('prev').click()};
  if (actions[key]) {event.preventDefault();actions[key]();}
});
$('comment').addEventListener('keydown', event => {if(event.key === 'Escape') $('comment').blur();});
load(0);
