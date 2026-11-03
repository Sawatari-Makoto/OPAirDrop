/**
 * Immersive layout for the module WebUI.
 *
 * The WebUI host varies between manager versions: some inject
 * `--window-inset-top` / `--window-inset-bottom`, some lay the WebView out
 * edge to edge and leave the safe area to the page, and some do neither and
 * simply place the viewport below the status bar. The page has to end up
 * correct in all three cases, so the insets are resolved here instead of
 * being assumed.
 *
 * Resolution order for the top inset:
 *   1. the value the module measured through the window manager
 *   2. the host injected `--window-inset-top`
 *   3. `env(safe-area-inset-top)` for hosts that lay out edge to edge
 *
 * The bottom inset follows the same order with `--window-inset-bottom` and
 * `env(safe-area-inset-bottom)`.
 */

/** Insets measured by the module backend, in CSS pixels. */
let measured = { top: 0, bottom: 0 };

/**
 * The last values written to the document element.
 *
 * The status poll runs every 500 ms and reports the same insets over and
 * over. Writing the custom properties again on every poll made the browser
 * re-resolve the whole style tree, which restarted the running CSS
 * transitions and produced the stuttering navigation bar and cut-off
 * page animations. The values are therefore only written when they change.
 */
let applied = { top: -1, bottom: -1 };

/**
 * Record the insets reported by the module backend.
 *
 * The measurement is authoritative because it comes from the window manager
 * for the window that is actually on screen.
 */
export function setMeasuredInsets(top: number, bottom: number): void {
  measured = {
    top: Number.isFinite(top) && top > 0 ? Math.round(top) : 0,
    bottom: Number.isFinite(bottom) && bottom > 0 ? Math.round(bottom) : 0,
  };
  applyInsets();
}

/** Host-injected inset, when the manager provides one. */
function hostInset(name: string): number {
  const raw = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  if (!raw || !raw.endsWith('px')) return 0;
  const value = Number.parseFloat(raw);
  return Number.isFinite(value) && value > 0 ? value : 0;
}

/** Safe area reported by an edge-to-edge host. */
function safeAreaInset(name: 'top' | 'bottom'): number {
  const probe = document.createElement('div');
  probe.style.cssText = 'position:fixed;top:0;left:0;visibility:hidden;pointer-events:none;';
  probe.style.setProperty(`padding-${name}`, `env(safe-area-inset-${name}, 0px)`);
  document.documentElement.appendChild(probe);
  const value = Number.parseFloat(getComputedStyle(probe).getPropertyValue(`padding-${name}`)) || 0;
  probe.remove();
  return value > 0 ? value : 0;
}

function applyInsets(): void {
  const root = document.documentElement;

  const top = measured.top || hostInset('--window-inset-top') || safeAreaInset('top');
  const bottom = measured.bottom || hostInset('--window-inset-bottom') || safeAreaInset('bottom');

  if (top !== applied.top) {
    root.style.setProperty('--top-inset', `${top}px`);
    applied.top = top;
  }
  if (bottom !== applied.bottom) {
    root.style.setProperty('--bottom-inset', `${bottom}px`);
    applied.bottom = bottom;
  }
  if (!root.classList.contains('immersive-ready')) {
    root.classList.add('immersive-ready');
  }
}

/**
 * The color the host paints the system bars with.
 *
 * The host tints the status bar and the gesture area from the
 * `<meta name="theme-color">` tag, but the value in the document is a static
 * approximation of the palette. On ColorOS 17 that approximation did not
 * match the surface the page actually paints, which left a visible strip of
 * a different color above the app and behind the gesture handle. Reading the
 * resolved custom property gives the exact color of the current theme, so
 * the bars blend into the page.
 *
 * The resolved value is normalised to a six-digit `#rrggbb` literal. The
 * palette stores the dark surface as `#000`; some native hosts hand the meta
 * value straight to an Android `Color.parseColor`, which rejects the
 * three-digit shorthand and then falls back to the host default — the exact
 * colour band that breaks the immersion.
 */
export function surfaceColor(): string {
  const raw = getComputedStyle(document.documentElement)
    .getPropertyValue('--m-color-surface')
    .trim();
  const value = normalizeHex(raw);
  return value || (document.documentElement.classList.contains('m-theme-dark') ? '#000000' : '#f7f7f7');
}

/** Expand `#rgb` to `#rrggbb`, otherwise return the input unchanged (or ''). */
function normalizeHex(color: string): string {
  const hex = color.replace(/^#/, '').toLowerCase();
  if (/^[0-9a-f]{3}$/.test(hex)) {
    return `#${hex.split('').map((c) => c + c).join('')}`;
  }
  return /^[0-9a-f]{6}$/.test(hex) ? `#${hex}` : '';
}

/**
 * Push the resolved surface color into the theme-color meta tag.
 *
 * The value is only written when it actually changes, for the same reason
 * the insets are only written on change.
 */
let appliedThemeColor = '';

export function syncThemeColor(): void {
  const meta = document.querySelector('meta[name="theme-color"]');
  if (!meta) return;
  const color = surfaceColor();
  if (color === appliedThemeColor) return;
  appliedThemeColor = color;
  meta.setAttribute('content', color);
}

/**
 * The host may report the insets only after the first layout, so the values
 * are recomputed when the viewport changes and after the UI has rendered.
 */
export function setupImmersiveLayout(): void {
  applyInsets();

  window.addEventListener('resize', applyInsets, { passive: true });
  window.addEventListener('orientationchange', applyInsets, { passive: true });

  if (typeof ResizeObserver === 'function') {
    const observer = new ResizeObserver(() => applyInsets());
    observer.observe(document.documentElement);
  }

  // Re-sync the system bar colour when the OS light/dark preference flips.
  // The theme class is normally toggled by the UI kit, but a host that keys
  // the palette off `prefers-color-scheme` alone may never mutate the class,
  // which the class observer would miss — leaving the bars one theme behind.
  if (typeof window.matchMedia === 'function') {
    const query = window.matchMedia('(prefers-color-scheme: dark)');
    const onSchemeChange = () => syncThemeColor();
    if (query.addEventListener) query.addEventListener('change', onSchemeChange);
    else if (query.addListener) query.addListener(onSchemeChange);
  }
}
