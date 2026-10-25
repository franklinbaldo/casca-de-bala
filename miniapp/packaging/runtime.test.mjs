import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
const build = process.env.B7_BUILD;
test('ZMake-built entry points register and execute lifecycle with API1 mocks', { skip: !build }, () => {
  const currentApp = { current: {} };
  const widgets = [];
  const context = vm.createContext({
    __$$hmAppManager$$__: { currentApp },
    DeviceRuntimeCore: { App: config => config, Page: config => config },
    hmUI: {
      widget: { TEXT: 1, BUTTON: 2 }, prop: { TEXT: 1 },
      createWidget(type, props) {
        const widget = { type, props, setProperty(_key, value) { this.props.text = value; } };
        widgets.push(widget);
        return widget;
      },
    },
  });
  vm.runInContext(fs.readFileSync(path.join(build, 'app.js'), 'utf8'), context);
  currentApp.app.onCreate();
  vm.runInContext(fs.readFileSync(path.join(build, 'page/index.js'), 'utf8'), context);
  const page = currentApp.current.module;
  assert.equal(page.build, undefined);
  page.onInit();
  assert.equal(widgets.length, 2);
  widgets[1].props.click_func();
  assert.equal(page.lastEvent.type, 'tap');
  assert.equal(page.lastEvent.sequence, 1);
  page.onDestroy();
  widgets[1].props.click_func();
  assert.equal(page.lastEvent, null);
  currentApp.app.onDestroy();
});
