'use strict';
/*
 * Runs in the page's own world (the 'main' shadow strategy, content.js): announces each shadow
 * root attached to an element already in the page, for the content script to watch. Pages can
 * see and undo this; it only helps find content, it guards nothing.
 */
(function () {
  var attach = Element.prototype.attachShadow;
  if (!attach || attach.__hisar) return;
  var hooked = function attachShadow() {
    var root = attach.apply(this, arguments);
    if (this.isConnected) {
      try { this.dispatchEvent(new CustomEvent('__hisar_shadow', { bubbles: true, composed: true })); } catch (e) {}
    }
    return root;
  };
  hooked.__hisar = true;
  Element.prototype.attachShadow = hooked;
})();
