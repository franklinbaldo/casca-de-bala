// Local UI prototype. No BLE or Side Service transport is attached.
Page({
  build() {
    this.active = true;
    this.sequence = 0;
    this.lastEvent = null;
    this.label = hmUI.createWidget(hmUI.widget.TEXT, {
      x: 12, y: 90, w: 168, h: 130,
      color: 0xffffff, text_size: 24,
      text: 'Band terminal\nOffline',
    });
    hmUI.createWidget(hmUI.widget.BUTTON, {
      x: 12, y: 260, w: 168, h: 70,
      text: 'Confirmar', text_size: 22,
      normal_color: 0x245a81, press_color: 0x397cac,
      click_func: () => {
        if (!this.active) return;
        // One bounded local event, not a delivery acknowledgement.
        this.sequence = Math.min(this.sequence + 1, 65535);
        this.lastEvent = { type: 'tap', sequence: this.sequence };
        this.label.setProperty(hmUI.prop.TEXT, 'Toque local: ' + this.sequence);
      },
    });
  },
  onDestroy() {
    this.active = false;
    this.lastEvent = null;
    this.label = null;
  },
});
