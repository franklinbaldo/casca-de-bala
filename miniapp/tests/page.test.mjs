import { test } from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';
const source = fs.readFileSync(new URL('../page/index.js', import.meta.url), 'utf8');
function page() {
  let definition;
  const widgets = [];
  vm.runInNewContext(source, {
    Page(value) { definition = value; },
    hmUI: {
      widget: { TEXT: 'TEXT', BUTTON: 'BUTTON' }, prop: { TEXT: 'text' },
      createWidget(type, props) {
        const widget = { type, props, setProperty(key, value) { this.props[key] = value; } };
        widgets.push(widget);
        return widget;
      },
    },
  });
  definition.build();
  return { definition, widgets };
}
test('local card and button fit the proposed 192x490 layout', () => {
  const { widgets } = page();
  assert.deepEqual(widgets.map(w => w.type), ['TEXT', 'BUTTON']);
  for (const { props: p } of widgets) {
    assert.ok(p.x >= 0 && p.y >= 0 && p.x+p.w <= 192 && p.y+p.h <= 490);
  }
});
test('tap updates local feedback and retains only the latest event', () => {
  const { definition: p, widgets } = page();
  const tap = widgets[1].props.click_func;
  tap(); tap();
  assert.equal(p.lastEvent.type, 'tap');
  assert.equal(p.lastEvent.sequence, 2);
  assert.equal(widgets[0].props.text, 'Toque local: 2');
  p.sequence = 65535;
  tap();
  assert.equal(p.sequence, 65535);
});
test('destroy ignores stale callback; new page resets session-local state', () => {
  const { definition: p, widgets } = page();
  p.onDestroy();
  widgets[1].props.click_func();
  assert.equal(p.lastEvent, null);
  assert.equal(p.label, null);
  const next = page().definition;
  assert.equal(next.sequence, 0);
  assert.equal(next.lastEvent, null);
});
