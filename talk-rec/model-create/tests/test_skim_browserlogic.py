"""Execute real app.js + HTTP server with a tiny DOM fixture (no browser install)."""
import json
import shutil
import subprocess
import threading

import pytest

from synth.cards import sample_cards
from synth.degrade import export
from synth.generate import fake_scene
from synth.io import read_jsonl
from tools.skim.serve import ASSETS, SkimServer, SkimStore


SCRIPT = r'''
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const [app, base, token] = process.argv.slice(2);
let active = null;
class Element {
  constructor(tag='div', value=null) { this.tag=tag;this.dataset={value};this.hidden=false;this.disabled=false;this.value='';this.textContent='';this.children=[];this.listeners={};this.attributes={}; }
  querySelectorAll() {return this.children;}
  replaceChildren(...values) {this.children=values;}
  setAttribute(k,v) {this.attributes[k]=v;}
  focus() {active=this;}
  blur() {active=null;}
  matches() {return this.tag==='textarea'||this.tag==='input';}
  addEventListener(k,fn) {this.listeners[k]=fn;}
  click() {if(!this.disabled) return this.onclick?.();}
}
const ids = Object.fromEntries(['real','verdict','comment','transcript','identity','progress','clean','intended','clean-lines','label-text','card','original','label','prev','next','status'].map(k=>[k,new Element(k==='comment'?'textarea':'div')]));
ids.real.children=['yes','no','maybe'].map(v=>new Element('button',v));
ids.verdict.children=['story','moment','none'].map(v=>new Element('button',v));
const listeners={};
const document={getElementById:id=>ids[id],querySelector:()=>({content:token}),createElement:tag=>new Element(tag),addEventListener:(k,fn)=>{listeners[k]=fn;}};
const context=vm.createContext({document,fetch:(url,opts)=>fetch(base+url,opts),console});
vm.runInContext(fs.readFileSync(app,'utf8'),context);
async function idle() {
  for(let i=0;i<200;i++){await new Promise(r=>setTimeout(r,10));if(!vm.runInContext('busy',context)) return;}
  throw Error('app did not become idle: '+ids.status.textContent);
}
function key(k) {listeners.keydown({key:k,target:active||new Element(),preventDefault(){},ctrlKey:false,altKey:false,metaKey:false,repeat:false});}
(async()=>{
  await idle();
  assert.ok(ids.transcript.children.length);
  assert.equal(ids.clean.hidden,true); assert.equal(ids.intended.hidden,true);
  assert.equal(ids['label-text'].textContent,'');
  key('o'); await idle(); assert.equal(ids.clean.hidden,false); assert.ok(ids['clean-lines'].children.length);
  key('l'); await idle(); assert.equal(ids.intended.hidden,false); assert.ok(ids['label-text'].textContent);
  key('c'); assert.equal(active,ids.comment);
  key('1'); await idle(); assert.equal(vm.runInContext('verdict',context),null);
  ids.comment.value='<script>ignored</script> · 자동 테스트';
  ids.comment.listeners.keydown({key:'Escape'}); assert.equal(active,null);
  key('n'); await idle(); assert.equal(vm.runInContext('item.index',context),0);
  key('3'); await idle(); assert.equal(vm.runInContext('item.index',context),1);
  assert.equal(ids.clean.hidden,true); assert.equal(ids.intended.hidden,true);
  key('k'); await idle(); assert.equal(vm.runInContext('item.index',context),0);
  assert.equal(vm.runInContext('real',context),'no');
  assert.equal(vm.runInContext('verdict',context),'none');
  assert.ok(ids.comment.value.includes('<script>'));
  key('j'); await idle(); assert.equal(vm.runInContext('item.index',context),1);
  // Leave item 2 undecided, save item 3: the UI must report remaining items.
  key('j'); await idle(); key('m'); await idle(); key('2'); await idle();
  assert.ok(ids.status.textContent.includes('미판정 1개'));
  console.log('UI interactions passed');
})().catch(e=>{console.error(e);process.exitCode=1;});
'''


def test_keyboard_buttons_auto_save_and_blind_reset(tmp_path):
    node = shutil.which('node')
    if not node:
        pytest.skip('Node is optional for the JS interaction test')
    cards = sample_cards(20)
    export(cards, [fake_scene(c) for c in cards], tmp_path, 'mixed')
    store = SkimStore(tmp_path, 'js-test', limit=3)
    script = tmp_path / 'ui-test.cjs'
    script.write_text(SCRIPT, encoding='utf-8')
    with SkimServer(('127.0.0.1', 0), store) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            result = subprocess.run([node, str(script), str(ASSETS / 'app.js'),
                                     f'http://127.0.0.1:{server.server_port}', server.token],
                                    capture_output=True, text=True, encoding='utf-8', timeout=20)
            assert result.returncode == 0, result.stdout + result.stderr
            assert 'UI interactions passed' in result.stdout
        finally:
            server.shutdown()
            thread.join(timeout=5)
            assert not thread.is_alive()
    rows = read_jsonl(store.path)
    assert len(rows) == 2
    first = next(r for r in rows if r['id'] == store.ids[0])
    assert first['opened_clean'] and first['revealed_label']
    assert first['real'] == 'no' and first['verdict'] == 'none'
    assert first['comment'] == '<script>ignored</script> · 자동 테스트'
