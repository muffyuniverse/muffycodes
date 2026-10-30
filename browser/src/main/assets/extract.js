/*
 * What a page says, as text an agent can read.
 *
 * **This file is JavaScript on purpose, and in `assets/` rather than in a Kotlin string.** It is the
 * one part of the browser that is *about the web* rather than about Android — the tag rules, what to
 * skip, how to keep a table readable — and it changes for reasons the surrounding code does not.
 * Held in a Kotlin string it would need escaping for every quote and backslash, could not be read by
 * anyone who does not build the app, and could not be tested on its own.
 *
 * The contract with the Kotlin side:
 *
 *   - an expression that evaluates to this file's value, run once per page;
 *   - it answers a **string** — the page's text — and never throws for an ordinary page;
 *   - it is also given the `eval` the caller asked for, already run, so it does not do that itself.
 *
 * The shape is moli's `--dump markdown` reduced to what an agent actually needs: the words, in
 * document order, with enough structure to tell a heading from a paragraph and a table row from a
 * sentence.
 */
(function () {
  'use strict';

  /** Elements whose text is never content. */
  var DROP = {
    SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEMPLATE: 1, IFRAME: 1, SVG: 1, CANVAS: 1,
    HEAD: 1, META: 1, LINK: 1, TITLE: 1
  };

  /** Elements that are a block of their own, so their text starts on a new line. */
  var BLOCK = {
    ADDRESS: 1, ARTICLE: 1, ASIDE: 1, BLOCKQUOTE: 1, DIV: 1, DL: 1, FIELDSET: 1,
    FIGCAPTION: 1, FIGURE: 1, FOOTER: 1, FORM: 1, H1: 1, H2: 1, H3: 1, H4: 1,
    H5: 1, H6: 1, HEADER: 1, HR: 1, MAIN: 1, NAV: 1, OL: 1, P: 1, PRE: 1,
    SECTION: 1, TABLE: 1, UL: 1
  };

  /** Elements that should be separated by one space rather than a new line. */
  var INLINE_SPACE = { BR: 1, TD: 1, TH: 1, LI: 1, DT: 1, DD: 1, TR: 1 };

  /** Collapses runs of whitespace, because HTML treats them as one space. */
  function tidy(s) {
    return s.replace(/[ \t\r\f\v]+/g, ' ').replace(/ ?\n ?/g, '\n');
  }

  /**
   * Whether this element is hidden from a reader.
   *
   * **Checked rather than trusted**: `innerText` already skips `display:none`, but this walker
   * builds its own text and would otherwise include a menu that is closed, a modal that is not
   * shown, or the "skip to content" link that every site keeps for screen readers. The computed
   * style is expensive, so it is asked once per element and only for the two properties that
   * matter.
   */
  function hidden(el) {
    var style;
    try {
      style = window.getComputedStyle(el);
    } catch (e) {
      return false;
    }
    if (!style) return false;
    if (style.display === 'none' || style.visibility === 'hidden') return true;
    // A zero-sized box is how most "closed" panels are hidden without `display`.
    var rect = el.getBoundingClientRect ? el.getBoundingClientRect() : null;
    if (rect && rect.width === 0 && rect.height === 0) return true;
    return false;
  }

  function walk(node, out) {
    if (node.nodeType === 3) { // text
      var text = node.nodeValue;
      if (text && text.trim()) out.push(text);
      return;
    }
    if (node.nodeType !== 1) return; // comments, and everything else
    var el = node;
    var tag = el.tagName ? el.tagName.toUpperCase() : '';
    if (DROP[tag]) return;
    if (el.getAttribute && el.getAttribute('aria-hidden') === 'true') return;
    if (hidden(el)) return;

    if (INLINE_SPACE[tag]) out.push('\n');
    else if (BLOCK[tag]) out.push('\n\n');

    // A table cell needs a separator, or every row becomes one long word.
    if (tag === 'TD' || tag === 'TH') out.push(' | ');

    for (var i = 0; i < el.childNodes.length; i++) walk(el.childNodes[i], out);

    if (INLINE_SPACE[tag] || BLOCK[tag]) out.push('\n');
  }

  var out = [];
  walk(document.body || document.documentElement, out);

  // Lines that are only a separator are noise: a table of one column would otherwise be all pipes.
  var lines = tidy(out.join('')).split('\n').map(function (line) {
    return line.replace(/^[\s|]+$/, '').trim();
  });

  // Collapse runs of blank lines to one, because the structure that matters is the paragraph.
  var kept = [];
  for (var i = 0; i < lines.length; i++) {
    if (lines[i] === '' && (kept.length === 0 || kept[kept.length - 1] === '')) continue;
    kept.push(lines[i]);
  }
  while (kept.length && kept[kept.length - 1] === '') kept.pop();

  return kept.join('\n');
})();
