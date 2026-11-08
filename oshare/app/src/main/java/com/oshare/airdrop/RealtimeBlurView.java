package com.oshare.airdrop;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;

/**
 * Realtime background blur, FreeShift style. Floating surfaces sample a
 * shared, downscaled capture of the content underneath, stack-blur it, and
 * draw the result inside their own outline. No external dependencies.
 *
 * CAPTURE SHARING (the smoothness story): every pane that floats over the
 * pager (title capsule, nav pill, top veil, notice card) used to re-draw
 * the WHOLE pager in software and stack-blur it inside its own onDraw —
 * 3-4 full software redraws of the page per scroll frame, which stalled
 * the frame no matter how slowly the finger moved. Now all panes sharing
 * one capture root register with a per-root {@link Hub}: each dirty frame
 * the pager is re-drawn ONCE and blurred once per frost recipe; every pane
 * then samples its own region from the same bitmap via a float-precise
 * matrix, so the frost tracks the content with no px-quantized jumps.
 *
 * SYNC: the hub's PreDraw hook marks the capture dirty on any content
 * change and records the root right there — preDraw dispatches at the top
 * of performDraw, BEFORE the framework records any view's RenderNode, so
 * the offscreen root.draw() can never collide with the live pass (capturing
 * inside a pane's onDraw re-entered ancestors' in-progress recordings on
 * this ColorOS/Android 15 build and crashed). Panes' onDraw in the same
 * frame finds the fresh node and draws the exact frame being displayed.
 *
 * The capture root must NOT contain the panes sampling it (never true:
 * blur surfaces float above the pager as siblings), so re-drawing the
 * root cannot recurse.
 */
final class RealtimeBlurView extends View {

    /** how much the captured frame is shrunk before blurring. 1/2 (was 1/10
     *  originally, then 1/4): the frost re-rasterizes the content every
     *  frame, and the scratch grid quantizes content motion into FACTOR-px
     *  screen steps — the blurred ghost's shape snaps to that grid even
     *  though pane sampling itself is float-precise + bilinear (filtering
     *  smooths the pane's position, not the captured content's grid). Each
     *  halving of FACTOR halves the step; 1/2 leaves a 2px step that the
     *  ≥5px on-screen blur kernel fully overlaps, and the band-clipped
     *  capture keeps the rasterization cost bounded to the sampled strips. */
    private static final int FACTOR = 2;

    /** one capture pipeline per distinct capture root */
    private static final java.util.WeakHashMap<View, Hub> HUBS = new java.util.WeakHashMap<>();

    private static final int[] HERE = new int[2];

    /** this view's box-blur radius, in scratch pixels (on-screen blur spread
     *  ≈ radius × FACTOR). Fractional radii below 1 blur with radius 1 and
     *  blend the result back toward the original by the fraction. */
    private float blurRadius = 10f;
    /** capture margin around the pane, in scratch pixels. The box blur clamps
     *  at the bitmap edge; without a margin that clamp smears the content
     *  into the visible pane interior and makes the boundary flicker as
     *  content scrolls past. The margin keeps the clamp artifacts outside. */
    private int pad = Math.max(1, (int) Math.ceil(blurRadius)) + 4;

    private Hub hub;
    /** where this pane's content origin sits in the shared capture, in
     *  scratch pixels (float — px-quantized origins would make the frost
     *  jump in FACTOR-px steps while a pane animates) */
    private float originX, originY;
    private final Matrix drawMatrix = new Matrix();

    private final Paint drawPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final RectF bounds = new RectF();

    private float cornerRadius = 0f;
    /** progressive dark/light veil towards the top edge (胶囊渐进模糊) */
    private int topFadeColor = Color.TRANSPARENT;
    private boolean topFade;

    /** alpha the blurred backdrop is drawn with (255 = fully opaque). Below
     *  255 the live content behind the pane reads through the frost for a
     *  thinner, more premium glass look. */
    private int drawAlpha = 255;

    /** color-mix layer (混色): a flat wash of material color blended over the
     *  blurred backdrop, iOS-frosted-glass style. This — not transparency —
     *  is what makes background text unreadable while keeping the blur's
     *  color variegation alive. */
    private int tintColor = 0x00000000;

    /** soft edge (无边界): instead of a hard clipped outline, the whole
     *  rendered pane is masked with concentric alpha rings so it dissolves
     *  into the background over ~feather px — no visible boundary line. */
    private boolean softEdge;
    private float feather;
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PorterDuffXfermode dstIn = new PorterDuffXfermode(PorterDuff.Mode.DST_IN);

    /** liquid glass (液态玻璃): saturation boost applied to the blurred
     *  backdrop. Real liquid glass reads vivid — the colours behind the
     *  pane come through richer, not washed out — so the frost gets its
     *  chroma pushed up hard instead of staying a flat grey haze. */
    private float liquidSat = 1f;

    /** brightness lift (玻璃亮度提升): glass reads a touch brighter than the
     *  matte page behind it. Light mode only — in dark mode ANY lift makes
     *  the pane lighter than the page, which re-draws the boundary the
     *  caller just feathered away. */
    private int glassLift = 0;

    /** glass sheen (玻璃斜向高光): a faint diagonal specular band drawn over
     *  the frost. Static reflection like a pane of glass catching light —
     *  this, plus the saturation lift, is what makes the surface read as
     *  glass rather than a translucent sheet. */
    private int sheenColor = 0x00000000;

    /** 0-1 multiplier on the rim's alpha so callers can fade the luminous
     *  boundary in and out (标题胶囊滚动淡入边界). 1f = full strength. */
    private float rimScale = 1f;

    /** opaque fill painted into the shared scratch before capturing the root.
     *  Without it the bitmap keeps transparent (=premultiplied black)
     *  pixels wherever the root does not paint, which the blur then
     *  smears into dark halos ("黑影"); an opaque base also guarantees
     *  the pane fully covers the live content behind it every frame. */
    private int bgFill = Color.WHITE;

    /** press glow (光效): radial highlight that follows the finger. Only
     *  alive while the nav bar is being touched: fades in on press, trails
     *  the finger, fades out on release. */
    private float glowX = -1f, glowTarget = -1f;
    private float glowAlpha = 0f;
    private android.animation.ValueAnimator glowFade;
    private int glowColor = 0x00000000;
    private boolean glowEnabled;
    /** glow size multiplier: 1 = default radius, 2 = double the light range */
    private float glowScale = 1f;

    /** luminous rim (光效描边): gradient stroke just inside the pane edge
     *  for a subtle 3D feel */
    private boolean rim;
    private float rimStroke;
    private int rimTop, rimBottom;
    /** true → the rim gradient is symmetric: bright at BOTH the top and the
     *  bottom edge, dim across the middle (rimBottom is the dim middle). */
    private boolean rimDual;
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** frost stack strength [0..1]: scales the backdrop blur, tint and sheen
     *  alphas WITHOUT touching the rim, glow or shadow. Used for scroll-linked
     *  fades where a view-level alpha would make the whole pane translucent
     *  and let raw content bleed through as a gray slab mid-fade. */
    private float frostScale = 1f;

    /** opaque page-colored backing under a mid-fade frost (see
     *  {@link #setFrostStrength}). Capsules want it (a half-faded capsule
     *  over live text reads as a gray slab); a full-width translucent veil
     *  does NOT — its whole point is to fade in as glass, letting the
     *  content behind read through at every strength. */
    private boolean backing = true;

    /** bottom fade mask (垂直渐隐): fractions of this view's height between
     *  which the frost's alpha dissolves from full (at fadeStart) to nothing
     *  (at fadeEnd). Above fadeStart the frost is undimmed. fadeStart >=
     *  fadeEnd (or < 0) disables the mask. Used by the full-width top veil
     *  so the frosted sheet dissolves into the page across the title
     *  capsule's band instead of ending on a hard horizontal line. */
    private float fadeStart = -1f, fadeEnd = -1f;

    RealtimeBlurView(android.content.Context ctx) {
        super(ctx);
        setWillNotDraw(false);
    }

    void setCaptureRoot(View root) {
        if (root == null) {
            hub = null;
            return;
        }
        Hub h = HUBS.get(root);
        if (h == null) {
            h = new Hub(root);
            HUBS.put(root, h);
        }
        hub = h;
        h.setFill(bgFill);
        h.views.add(new java.lang.ref.WeakReference<>(this));
    }

    void setCornerRadius(float px) {
        cornerRadius = px;
        invalidate();
    }

    /** Set this view's box-blur radius in scratch pixels (on-screen spread
     *  ≈ radius × FACTOR); 0 = no blur at all, fractions below 1 are
     *  approximated by blending a radius-1 blur with the sharp capture. */
    void setBlurRadius(float r) {
        r = Math.max(0f, r);
        if (r == blurRadius) return;
        blurRadius = r;
        pad = Math.max(1, (int) Math.ceil(r)) + 4;
        invalidate();
    }

    /** Draw the blurred backdrop with the given alpha (0-255) so the live
     *  content behind the pane reads through the frost. */
    void setOpacity(int alpha) {
        drawAlpha = Math.max(0, Math.min(255, alpha));
        invalidate();
    }

    /** Set the opaque base color the capture canvas is cleared with. */
    void setFill(int color) {
        bgFill = color | 0xFF000000;
        if (hub != null) hub.setFill(bgFill);
        invalidate();
    }

    /** Blend a flat material-color wash over the blurred backdrop (混色). */
    void setTint(int color) {
        tintColor = color;
        invalidate();
    }

    /** Dissolve the pane edges with a feathered mask instead of clipping to
     *  a hard outline (标题胶囊去掉边界). */
    void setSoftEdge(boolean enabled, float featherPx) {
        softEdge = enabled;
        feather = featherPx;
        invalidate();
    }

    void setTopFade(boolean enabled, int veilColor) {
        topFade = enabled;
        topFadeColor = veilColor;
        invalidate();
    }

    /** Liquid-glass frost: boost the saturation of the blurred backdrop so
     *  the pane reads vivid and glassy instead of flat grey, and enable the
     *  diagonal specular sheen. */
    void setLiquidGlass(boolean on) {
        liquidSat = on ? 2.2f : 1f;
        invalidate();
    }

    /** Glass sheen color (玻璃斜向高光): faint diagonal specular band drawn
     *  over the frost; 0 disables. */
    void setSheen(int color) {
        sheenColor = color;
        invalidate();
    }

    /** Uniform brightness added to the liquid-glass frost (0-255). Keep 0
     *  in dark mode so the pane never outshines the page boundary-free. */
    void setGlassLift(int lift) {
        glassLift = Math.max(0, Math.min(255, lift));
        invalidate();
    }

    /** Fade the luminous rim between invisible (0) and full strength (1). */
    void setRimStrength(float s) {
        rimScale = Math.max(0f, Math.min(1f, s));
        invalidate();
    }

    void setGlow(boolean enabled, int color) {
        glowEnabled = enabled;
        glowColor = color;
        invalidate();
    }

    /** Multiply the glow's light range (1 = default, 2 = double). */
    void setGlowScale(float s) {
        glowScale = Math.max(0.1f, s);
        invalidate();
    }

    /** Plain tracking glow: no satellite particles, no random wandering, no
     *  white-hot ignition core and no fade-in radius swell — just the tinted
     *  bloom following the finger with its fade in/out. Used for the dark
     *  nav bar so its glow reads exactly like the light mode's base glow. */
    private boolean glowPlain;

    void setGlowPlain(boolean p) {
        glowPlain = p;
        invalidate();
    }

    /** particle sparkle layer (粒子光效): satellite sparks scattered around
     *  the finger glow — twinkling on their own clocks while the finger
     *  rests, streaming behind the motion while it slides. */
    private boolean glowParticles;
    private static final int PARTICLE_N = 6;
    /** per-particle: {trail lag, vertical scatter, radius fraction,
     *  twinkle phase, twinkle speed, x-wander speed, y-wander speed} */
    private final float[][] particleSpec = new float[PARTICLE_N][7];

    /** Enable the satellite-particle sparkle layer on top of the glow. */
    void setGlowParticles(boolean on) {
        glowParticles = on;
        if (particleSpec[0][2] == 0f) seedParticles();
        invalidate();
    }

    private void seedParticles() {
        java.util.Random rnd = new java.util.Random(20261107L);
        for (int i = 0; i < PARTICLE_N; i++) {
            particleSpec[i][0] = 0.15f + 0.55f * rnd.nextFloat();  // trail lag
            particleSpec[i][1] = (rnd.nextFloat() * 2f - 1f) * 0.16f; // y scatter
            particleSpec[i][2] = 0.16f + 0.22f * rnd.nextFloat();  // radius frac
            particleSpec[i][3] = (float) (rnd.nextDouble() * Math.PI * 2); // phase
            particleSpec[i][4] = 3.5f + 4.5f * rnd.nextFloat();    // twinkle speed
            particleSpec[i][5] = 1.2f + 2.8f * rnd.nextFloat();    // x-wander speed
            particleSpec[i][6] = 1.2f + 2.8f * rnd.nextFloat();    // y-wander speed
        }
    }

    /** Set the vertical fade band: full frost above `startF` (fraction of
     *  height), alpha dissolving linearly to zero at `endF`. Pass
     *  startF >= endF to disable. */
    void setVerticalFade(float startF, float endF) {
        if (startF == fadeStart && endF == fadeEnd) return;
        fadeStart = startF;
        fadeEnd = endF;
        invalidate();
    }

    /** Fade the frost stack (backdrop blur + tint + sheen) in/out, leaving
     *  the rim, glow and cast shadow to their own controls. While the fade is
     *  in progress (0 < strength < 1) an opaque page-colored backing is drawn
     *  under the frost, so live content never bleeds through the pane as a
     *  gray veil mid-fade — the fill dissolves from page color into frost
     *  instead. At strength 0 nothing is drawn at all; at strength 1 the pane
     *  renders exactly as before (no backing, original alphas). */
    void setFrostStrength(float s) {
        s = Math.max(0f, Math.min(1f, s));
        if (s == frostScale) return;
        frostScale = s;
        invalidate();
    }

    /** Toggle the opaque page-colored backing drawn under a mid-fade frost.
     *  Disable for translucent full-width veils that fade in as glass. */
    void setBackingEnabled(boolean on) {
        backing = on;
        invalidate();
    }

    /** Enable the 3D luminous rim: gradient stroke of the given width,
     *  brighter at the top edge, dimmer at the bottom. */
    void setRim(boolean enabled, float strokeWidthPx, int topColor, int bottomColor) {
        rim = enabled;
        rimStroke = strokeWidthPx;
        rimTop = topColor;
        rimBottom = bottomColor;
        invalidate();
    }

    /** Make the rim symmetric: bright at the top AND bottom edge, dim in
     *  the middle (bottomColor doubles as the dim middle tone). */
    void setRimDual(boolean dual) {
        rimDual = dual;
        invalidate();
    }

    /** Fade the press glow IN, centred on the press point. */
    void showGlow(float x) {
        glowTarget = x;
        if (glowX < 0) glowX = x;
        if (glowFade != null) glowFade.cancel();
        glowFade = android.animation.ValueAnimator.ofFloat(glowAlpha, 1f);
        glowFade.setDuration(160);
        glowFade.addUpdateListener(a -> {
            glowAlpha = (Float) a.getAnimatedValue();
            invalidate();
        });
        glowFade.start();
    }

    /** Fade the press glow OUT after release. */
    void hideGlow() {
        if (glowFade != null) glowFade.cancel();
        glowFade = android.animation.ValueAnimator.ofFloat(glowAlpha, 0f);
        glowFade.setDuration(240);
        glowFade.addUpdateListener(a -> {
            glowAlpha = (Float) a.getAnimatedValue();
            invalidate();
        });
        glowFade.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (glowAlpha <= 0f) glowX = -1f;
            }
        });
        glowFade.start();
    }

    /** Move the press glow smoothly to the given x within this view. */
    void moveGlow(float x) {
        glowTarget = x;
        if (glowX < 0) glowX = x;
        invalidate();
    }

    /** Force a re-capture on the next animation frame. Used to keep the blur
     *  in lockstep with content that scrolls without triggering a full
     *  invalidate pass of its own (e.g. pager page transitions). */
    void refresh() {
        if (hub != null) hub.dirty = true;
        postInvalidateOnAnimation();
    }

    /** Synchronous capture right now. Only call OUTSIDE any draw pass (e.g.
     *  right after building a floating overlay / showing a dialog): panes
     *  that join mid-interaction would otherwise spend their first frame on
     *  the flat backing while the deferred post() capture catches up. */
    void captureNow() {
        if (hub == null) return;
        int w = hub.root.getWidth(), h = hub.root.getHeight();
        if (w != 0 && h != 0) hub.record(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        // the pane is a full capsule: never let a caller-set radius exceed
        // half the height, or the rounded path self-intersects
        float radius = Math.min(cornerRadius > 0 ? cornerRadius : h / 2f, h / 2f);
        bounds.set(0, 0, w, h);

        // soft edge: render into an offscreen layer, clip to the capsule AND
        // mask it with a feathered round rect so the edge dissolves instead
        // of ending on a hard line (the clip is the safety net; the mask is
        // the dissolve). hard edge: plain rounded clip as before.
        int base;
        boolean soft = softEdge && feather > 0f && radius > 0f;
        // vertical fade mask needs an offscreen layer too (the DST_IN gradient
        // trims the layer's alpha at the end of the draw)
        boolean grad = fadeStart >= 0f && fadeEnd > fadeStart && fadeEnd <= 1f;
        // during a frost fade (0 < strength < 1) an opaque page-colored
        // backing hides the raw content behind the pane — without it the
        // semi-transparent frost lets content bleed through and read as a
        // gray slab mid-fade. At rest (strength 0 or 1) no backing, so the
        // resting look (and the nav pill, which never fades) is untouched.
        boolean fading = backing && frostScale > 0f && frostScale < 1f;
        if (soft) {
            base = c.saveLayer(0, 0, w, h, null);
            clipPath.reset();
            clipPath.addRoundRect(bounds, radius, radius, Path.Direction.CW);
            c.clipPath(clipPath);
            // opaque page-colored backing: the feather rings trim alpha
            // towards the boundary — without a backing the live content
            // bleeds through that semi-transparent ring and reads as an
            // INNER SHADOW inside the capsule. Fading onto an opaque
            // base of the page color dissolves into the background
            // instead of into whatever happens to sit behind the edge.
            overlayPaint.setShader(null);
            overlayPaint.setColor(bgFill);
            c.drawRect(bounds, overlayPaint);
        } else if (grad) {
            base = c.saveLayer(0, 0, w, h, null);
            if (radius > 0) {
                clipPath.reset();
                clipPath.addRoundRect(bounds, radius, radius, Path.Direction.CW);
                c.clipPath(clipPath);
            }
            if (fading) {
                overlayPaint.setShader(null);
                overlayPaint.setColor(bgFill);
                c.drawRect(bounds, overlayPaint);
            }
        } else {
            base = c.save();
            if (radius > 0) {
                clipPath.reset();
                clipPath.addRoundRect(bounds, radius, radius, Path.Direction.CW);
                c.clipPath(clipPath);
            }
            if (fading) {
                overlayPaint.setShader(null);
                overlayPaint.setColor(bgFill);
                c.drawRect(bounds, overlayPaint);
            }
        }

        // re-entrant pane: this onDraw is running INSIDE a root capture
        // traversal (this pane sits under the capture root) or while a
        // deferred capture is still queued for this live frame. Skip the
        // whole capture pipeline — both the GPU record() and the software
        // capture()→drawRaw() would recurse into root.draw() again and
        // crash ("Recording currently in progress"). Just paint the flat
        // backing color; the NEXT live frame renders the real frost.
        boolean nested = hub != null && (hub.recording || hub.recQueued);
        if (frostScale > 0f && !nested) {
            // shared full-resolution capture RenderNode; rasterization AND
            // blur run on the GPU with zero quantization — the frost ghost
            // tracks content pixel-for-pixel, eliminating the scratch-grid
            // stepping entirely. The software bitmap pipeline below stays
            // as the fallback for non-hardware canvases.
            boolean gpu = c.isHardwareAccelerated() && hub != null
                && hub.drawInto(c, this, blurRadius, liquidSat, glassLift,
                    (int) (drawAlpha * frostScale));
            if (!gpu) {
                Bitmap src = capture();
                if (src != null) {
                    // sample this pane's region out of the shared full-root
                    // capture with a float-precise matrix: content origin →
                    // view origin, ×FACTOR back up to screen scale. The pad
                    // ring lands outside the view's clip, so only the inner
                    // region shows (same as the old srcRect draw).
                    drawMatrix.reset();
                    drawMatrix.setTranslate(-originX, -originY);
                    drawMatrix.postScale(FACTOR, FACTOR);
                    drawPaint.setAlpha((int) (drawAlpha * frostScale));
                    c.drawBitmap(src, drawMatrix, drawPaint);
                }
            }
        }

        // color mix (混色): a flat wash of the material color over the blur —
        // this is what hides the text behind the pane while the blur keeps
        // its soft color variegation
        int tintA = (int) ((tintColor >>> 24) * frostScale);
        if (tintA != 0) {
            overlayPaint.setShader(null);
            overlayPaint.setColor((tintA << 24) | (tintColor & 0x00FFFFFF));
            c.drawRect(bounds, overlayPaint);
        }

        // glass sheen (液态玻璃斜向高光): a faint diagonal specular band, like
        // a pane of glass catching the light — reads as reflection, not haze
        int sheenA = (int) ((sheenColor >>> 24) * frostScale);
        if (sheenA != 0) {
            overlayPaint.setShader(new LinearGradient(
                0, 0, w, h,
                new int[]{0x00000000, (sheenA << 24) | (sheenColor & 0x00FFFFFF), 0x00000000},
                new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(bounds, overlayPaint);
            overlayPaint.setShader(null);
        }

        if (topFade) {
            overlayPaint.setShader(new LinearGradient(
                0, 0, 0, h * 0.85f,
                topFadeColor, (topFadeColor & 0x00FFFFFF),
                Shader.TileMode.CLAMP));
            c.drawRect(bounds, overlayPaint);
            overlayPaint.setShader(null);
        }

        float glowA = 0f;
        if (glowEnabled && glowX >= 0) glowA = glowAlpha;
        int glowRgb = glowColor & 0x00FFFFFF;

        if (glowA > 0f) {
            // ease towards the target so the glow trails the finger slightly;
            // keep invalidating while visible so the particle layer keeps
            // twinkling and wandering even when the finger rests
            float trail = glowTarget - glowX;
            glowX += trail * 0.3f;
            if (Math.abs(glowTarget - glowX) > 0.5f || glowParticles)
                postInvalidateOnAnimation();
            // bloom: the tinted body follows the finger; unless the plain
            // mode is set, it carries a white-hot core inside and its
            // radius swells while fading in so the touch visibly "ignites"
            int hr = glowRgb >> 16 & 0xFF, hg = glowRgb >> 8 & 0xFF,
                hb = glowRgb & 0xFF;
            int hotRgb = Color.rgb(
                hr + (0xFF - hr) * 6 / 10,
                hg + (0xFF - hg) * 6 / 10,
                hb + (0xFF - hb) * 6 / 10);
            int coreRgb = glowPlain ? glowRgb : hotRgb;
            float r = Math.max(w, h) * 0.28f * glowScale
                * (glowPlain ? 1f : (0.78f + 0.22f * glowA));
            int a = (int) ((glowColor >>> 24) * glowA);
            overlayPaint.setShader(new RadialGradient(
                glowX, h / 2f, r,
                new int[]{(a << 24) | coreRgb, (a << 24) | glowRgb,
                    ((a / 3) << 24) | glowRgb, 0x00000000},
                new float[]{0f, 0.22f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(bounds, overlayPaint);
            overlayPaint.setShader(null);

            if (glowParticles && !glowPlain) {
                // satellite sparks: each drifts behind the finger's motion,
                // twinkles on its own clock, and WANDERS on a random walk —
                // while the finger slides, motion energy makes them scatter
                // outward (diverge) so the comet visibly breaks into sparks
                long now = android.view.animation.AnimationUtils
                    .currentAnimationTimeMillis();
                // normalized motion energy: 0 at rest, 1 on a fast slide
                float energy = Math.min(1f, Math.abs(trail) / (w * 0.25f));
                for (int i = 0; i < PARTICLE_N; i++) {
                    float[] ps = particleSpec[i];
                    float tw = 0.5f + 0.5f * (float) Math.sin(
                        now * 0.001f * ps[4] * (float) Math.PI * 2 + ps[3]);
                    float pa = glowA * (0.25f + 0.75f * tw);
                    if (pa <= 0.02f) continue;
                    // random walk: two out-of-phase oscillators per axis —
                    // amplitude swells with motion energy (divergence)
                    float wx = (float) Math.sin(now * 0.001f * ps[5]
                        * (float) Math.PI * 2 + ps[3]);
                    float wy = (float) Math.cos(now * 0.001f * ps[6]
                        * (float) Math.PI * 2 + ps[3] * 1.7f);
                    float diverge = 0.10f + 0.45f * energy;
                    float px = glowX - trail * ps[0]
                        + wx * diverge * r;
                    float py = h / 2f + ps[1] * h * (0.6f + 0.9f * energy)
                        + wy * diverge * r;
                    float pr = r * ps[2];
                    int pga = (int) ((glowColor >>> 24) * pa);
                    overlayPaint.setShader(new RadialGradient(
                        px, py, pr,
                        new int[]{(pga << 24) | hotRgb,
                            ((pga / 2) << 24) | glowRgb, 0x00000000},
                        new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP));
                    c.drawRect(px - pr, py - pr, px + pr, py + pr, overlayPaint);
                }
                overlayPaint.setShader(null);
            }
        }

        if (rim && rimStroke > 0f && rimScale > 0f) {
            // luminous edge: rimDual lights BOTH the top and the bottom
            // edge (bright → dim middle → bright); otherwise bright top
            // fading down the sides. rimScale fades the whole boundary,
            // so a caller can reveal it gradually (scroll-linked fade).
            rimPaint.setStyle(Paint.Style.STROKE);
            rimPaint.setStrokeWidth(rimStroke);
            int ta = (int) ((rimTop >>> 24) * rimScale);
            int ba = (int) ((rimBottom >>> 24) * rimScale);
            int topC = (ta << 24) | (rimTop & 0x00FFFFFF);
            int botC = (ba << 24) | (rimBottom & 0x00FFFFFF);
            rimPaint.setShader(rimDual
                ? new LinearGradient(0, 0, 0, h,
                    new int[]{topC, botC, topC},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP)
                : new LinearGradient(0, 0, 0, h, topC, botC,
                    Shader.TileMode.CLAMP));
            float half = rimStroke / 2f;
            c.drawRoundRect(half, half, w - half, h - half, radius, radius, rimPaint);
            if (glowA > 0f) {
                // travelling specular: the edge brightens in a window that
                // follows the finger, so the boundary itself catches the
                // light instead of only the interior
                float span = w * 0.38f;
                int sa = (int) (0xB3 * glowA);
                rimPaint.setShader(new LinearGradient(
                    glowX - span, 0, glowX + span, 0,
                    new int[]{0x00000000, (sa << 24) | glowRgb,
                        0x00000000},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                c.drawRoundRect(half, half, w - half, h - half, radius, radius, rimPaint);
            }
            rimPaint.setShader(null);
        }

        if (grad) {
            // vertical fade: the layer keeps full alpha above fadeStart, then
            // dissolves linearly to nothing at fadeEnd. DST_IN keeps the
            // destination where the source (this gradient) is opaque.
            maskPaint.setStyle(Paint.Style.FILL);
            maskPaint.setXfermode(dstIn);
            maskPaint.setShader(new LinearGradient(
                0, 0, 0, h,
                new int[]{0xFF000000, 0xFF000000, 0x00000000, 0x00000000},
                new float[]{0f, fadeStart, fadeEnd, 1f},
                Shader.TileMode.CLAMP));
            c.drawRect(bounds, maskPaint);
            maskPaint.setXfermode(null);
            maskPaint.setShader(null);
        }

        if (soft) {
            // feathered edge without BlurMaskFilter (which silently no-ops
            // behind a xfermode here): concentric DST_IN rings trim the
            // layer's alpha in steps towards the boundary, building a
            // smooth dissolve over ~feather px
            final int steps = 5;
            float fw = feather / steps;
            maskPaint.setStyle(Paint.Style.STROKE);
            maskPaint.setXfermode(dstIn);
            for (int i = 0; i < steps; i++) {
                float half = fw / 2f + fw * i;
                int a = (255 * (i + 1)) / steps;
                maskPaint.setColor(a << 24);
                maskPaint.setStrokeWidth(fw);
                c.drawRoundRect(half, half, w - half, h - half,
                    radius, radius, maskPaint);
            }
            maskPaint.setXfermode(null);
            maskPaint.setStyle(Paint.Style.FILL);
        }
        c.restoreToCount(base);
    }

    /** Fetch this pane's blurred backdrop from the shared hub and record
     *  where the pane's content origin sits inside it (scratch px). */
    private Bitmap capture() {
        if (hub == null || hub.root.getWidth() == 0) return null;
        Bitmap b = hub.acquire(blurRadius, liquidSat, glassLift);
        if (b == null) return null;
        getLocationOnScreen(HERE);
        originX = (HERE[0] - hub.rootLoc[0]) / (float) FACTOR;
        originY = (HERE[1] - hub.rootLoc[1]) / (float) FACTOR;
        return b;
    }

    /** Shared capture pipeline for every blur pane sampling the same root.
     *  One scratch bitmap covers the whole root at 1/FACTOR; a dirty frame
     *  re-draws the root once and blurs it once per distinct frost recipe.
     *  Panes fetch the result in their onDraw. */
    private static final class Hub {
        final View root;
        final java.util.ArrayList<java.lang.ref.WeakReference<RealtimeBlurView>> views =
            new java.util.ArrayList<>();
        int fill = Color.WHITE;
        /** set by the root's PreDraw (any content change) and by refresh();
         *  cleared once the scratch is re-drawn */
        boolean dirty = true;
        /** re-entrancy guard: true while root.draw() is traversing INSIDE a
         *  capture. A pane that lives under the capture root (e.g. the
         *  history sheet's card blur, whose captureRoot IS that root) gets
         *  its onDraw invoked during the traversal — without this guard it
         *  re-enters record()/drawRaw() and RenderNode throws
         *  "Recording currently in progress". During a traversal every
         *  pane just paints its flat backing color instead of capturing. */
        boolean recording;
        /** a deferred capture is queued via root.post() — set while a live
         *  frame is still on the stack, so panes that onDraw during that
         *  frame (including the initiating pane's own software fallback)
         *  skip the whole capture pipeline and just paint their backing */
        boolean recQueued;
        boolean rawValid;
        Bitmap raw;
        final Canvas rawCanvas = new Canvas();
        /** blurred scratch copies, one per frost recipe in use */
        final java.util.ArrayList<Blurred> recipes = new java.util.ArrayList<>();
        int rawStamp;
        /** scratch-px vertical bands [top, bottom] the visible panes sample,
         *  recomputed on each raw draw; the blur is restricted to these rows */
        final java.util.ArrayList<float[]> bands = new java.util.ArrayList<>();
        /** root's on-screen location at the last capture (panes map their
         *  region against this) */
        final int[] rootLoc = new int[2];
        /** union clip of all bands' content-space rects, reused per frame */
        final android.graphics.Path bandPath = new android.graphics.Path();

        // --- GPU pipeline (hardware-accelerated windows) ---
        /** full-resolution capture of the root as a display list. Rasterized
         *  and blurred ON THE GPU when panes draw it — no scratch grid, so
         *  no FACTOR-px stepping; the CPU only records draw commands. */
        RenderNode node;
        boolean nodeValid;
        /** active GPU recipe, so the effect chain is rebuilt only on change */
        float gRadius = -1f, gSat = -1f;
        int gLift = -1;
        /** gaussian blur radius (px) baked into the cached RenderEffect */
        float gBlurPx = -1f;
        /** gaussian-vs-box conversion: the CPU recipe's box radius R spreads
         *  content over ~2R px; a gaussian with radius R needs ~1.8R to
         *  match its visual spread. Tuned against screenshots. */
        static final float GPU_BLUR_SCALE = 1.8f;

        /**
         * Record the root's current content into the shared RenderNode.
         * Command recording only (CPU, ~1ms); rasterization happens on the
         * GPU thread when a pane draws the node.
         *
         * Coordinates: the recorded display list is in VIEWPORT coords —
         * content where it sits on screen right now. Offline recording
         * does NOT get the framework's free scroll offset (that shift only
         * happens in the live render pass), so the root's own scroll is
         * translated manually here. Verified on device: without it the
         * capture sat in layout coords and every pane ghosted page 1's
         * content while another tab was showing; with the pane alignment
         * ALSO subtracting scroll (the v54 bug) it double-applied and
         * sampled past the last page, blanking all frost.
         */
        private void record(int w, int h) {
            // re-entrant call from a nested pane's onDraw (see `recording`)
            if (recording) return;
            if (node == null) node = new RenderNode("frost-capture");
            node.setPosition(0, 0, w, h);
            recording = true;
            try {
                Canvas rc = node.beginRecording(w, h);
                try {
                    // opaque page base — same role as the scratch eraseColor
                    rc.drawColor(fill);
                    rc.translate(-root.getScrollX(), -root.getScrollY());
                    root.draw(rc);
                } finally {
                    node.endRecording();
                }
            } finally {
                recording = false;
            }
            nodeValid = true;
            dirty = false;
        }

        /** (Re)build the blur+saturation+lift effect chain for a recipe. */
        private void ensureEffect(float radius, float sat, int lift) {
            if (radius == gRadius && sat == gSat && lift == gLift) return;
            gRadius = radius; gSat = sat; gLift = lift;
            // the API radius is in scratch px (software fallback); at full
            // GPU resolution the on-screen spread is radius × FACTOR
            float rpx = Math.max(1f, radius * FACTOR);
            gBlurPx = rpx;
            float gr = rpx * GPU_BLUR_SCALE;
            if (sat != 1f || lift != 0) {
                ColorMatrix cm = new ColorMatrix();
                cm.setSaturation(sat);
                float o = lift / 255f;
                ColorMatrix lm = new ColorMatrix(new float[]{
                    1, 0, 0, 0, o,
                    0, 1, 0, 0, o,
                    0, 0, 1, 0, o,
                    0, 0, 0, 1, 0});
                ColorMatrix both = new ColorMatrix();
                both.setConcat(lm, cm); // saturate, then lift
                // chain: colorMatrix ∘ blur (outer applied last). The SDK
                // exposes no createColorMatrixEffect — ColorFilter route.
                node.setRenderEffect(RenderEffect.createChainEffect(
                    RenderEffect.createColorFilterEffect(
                        new android.graphics.ColorMatrixColorFilter(both)),
                    RenderEffect.createBlurEffect(gr, gr,
                        Shader.TileMode.CLAMP)));
            } else {
                node.setRenderEffect(RenderEffect.createBlurEffect(
                    gr, gr, Shader.TileMode.CLAMP));
            }
        }

        /** GPU draw path: record the root if dirty, then draw the blurred
         *  capture node into the pane's canvas so content aligns 1:1 with
         *  what sits behind the pane. Returns false when the pipeline can't
         *  run (caller falls back to the software bitmap path). The node
         *  holds VIEWPORT coordinates (content where it sits on screen),
         *  so a pane at screen offset (px, py) from the root just draws
         *  the node translated by -(px, py) — no scroll term. */
        boolean drawInto(Canvas c, RealtimeBlurView pane,
                         float radius, float sat, int lift, int alpha) {
            // nested pane during a root traversal: never capture, never fall
            // through to the software path (that would recurse too)
            if (recording) return false;
            int rw = root.getWidth(), rh = root.getHeight();
            if (rw == 0 || rh == 0) return false;
            if (dirty || !nodeValid
                || node.getWidth() != rw || node.getHeight() != rh) {
                // NEVER draw() the root from inside a live frame: on this
                // ColorOS/Android 15 build several ancestors of the panes
                // record their OWN RenderNodes during the live pass (the
                // crash stacks show View.updateDisplayListIfDirty →
                // beginRecording nested inside the live chain), and an
                // offscreen traversal re-entering them hits beginRecording
                // on a node that is "Recording currently in progress".
                // Post the capture instead: the runnable runs after
                // performTraversals, when NOTHING is recording, so the
                // offscreen tree walk is safe. Panes paint their flat
                // backing this frame (the software band path still covers
                // the initiating pane) and get the real frost next frame.
                if (!recQueued) {
                    recQueued = true;
                    root.post(() -> {
                        recQueued = false;
                        int w = root.getWidth(), h = root.getHeight();
                        if (w == 0 || h == 0) return;
                        record(w, h);
                        for (int i = 0; i < views.size(); i++) {
                            RealtimeBlurView v = views.get(i).get();
                            if (v != null && v.isAttachedToWindow()
                                && v.getVisibility() == VISIBLE) {
                                v.postInvalidateOnAnimation();
                            }
                        }
                    });
                }
                return false;
            }
            if (!nodeValid) return false;
            ensureEffect(radius, sat, lift);
            root.getLocationOnScreen(rootLoc);
            pane.getLocationOnScreen(HERE);
            // content px p (viewport) lands at pane-local p - (paneOnScreen
            // - rootOnScreen), 1:1, no resampling
            c.save();
            int layer = -1;
            if (alpha < 255) {
                layer = c.saveLayerAlpha(0, 0, pane.getWidth(),
                    pane.getHeight(), alpha);
            }
            c.translate(-(HERE[0] - rootLoc[0]), -(HERE[1] - rootLoc[1]));
            c.drawRenderNode(node);
            if (layer >= 0) c.restoreToCount(layer); else c.restore();
            return true;
        }

        static final class Blurred {
            float radius, sat;
            int lift;
            Bitmap bmp;
            Canvas canvas = new Canvas();
            int stamp = -1;
            Blurred(float r, float s, int l) { radius = r; sat = s; lift = l; }
        }

        Hub(View root) {
            this.root = root;
            root.getViewTreeObserver().addOnPreDrawListener(() -> {
                boolean any = false;
                for (int i = 0; i < views.size(); i++) {
                    RealtimeBlurView v = views.get(i).get();
                    if (v == null) { views.remove(i--); continue; }
                    if (v.isAttachedToWindow() && v.getVisibility() == VISIBLE)
                        any = true;
                }
                if (!any) return true;
                dirty = true;
                // Capture NOW, at PreDraw: preDraw listeners are dispatched
                // at the TOP of performDraw, before the framework records
                // ANY view's RenderNode for the live pass — the offscreen
                // root.draw() here can never collide with an in-progress
                // beginRecording. (v69 captured lazily inside the first
                // pane's onDraw, which runs in the MIDDLE of the live pass
                // where ancestors record their own nodes — "Recording
                // currently in progress" crash — and the post() fallback
                // forced the heavy software band pipeline every animation
                // frame: jank, stepping, flat semi-transparent flashes.)
                // After this record the panes' onDraw in the SAME frame
                // finds a valid node and draws the GPU frost 1:1.
                int w = root.getWidth(), h = root.getHeight();
                if (w != 0 && h != 0) record(w, h);
                for (int i = 0; i < views.size(); i++) {
                    RealtimeBlurView v = views.get(i).get();
                    if (v != null && v.isAttachedToWindow()
                        && v.getVisibility() == VISIBLE) {
                        v.postInvalidateOnAnimation();
                    }
                }
                return true;
            });
        }

        void setFill(int color) {
            if (fill != color) {
                fill = color;
                dirty = true;
            }
        }

        /** Return the blurred full-root bitmap for the given frost recipe,
         *  re-capturing and re-blurring only what changed since the last
         *  frame. */
        Bitmap acquire(float radius, float sat, int lift) {
            int bw = Math.max(1, root.getWidth() / FACTOR);
            int bh = Math.max(1, root.getHeight() / FACTOR);
            if (raw == null || raw.getWidth() != bw || raw.getHeight() != bh) {
                raw = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                rawCanvas.setBitmap(raw);
                rawValid = false;
            }
            if (dirty || !rawValid) {
                drawRaw();
                dirty = false;
                rawValid = true;
                rawStamp++;
            }
            Blurred e = null;
            for (int i = 0; i < recipes.size(); i++) {
                Blurred r = recipes.get(i);
                if (r.radius == radius && r.sat == sat && r.lift == lift) { e = r; break; }
            }
            if (e == null) {
                if (recipes.size() >= 4) e = recipes.get(0);
                else { e = new Blurred(radius, sat, lift); recipes.add(e); }
                if (e.bmp == null || e.bmp.getWidth() != bw || e.bmp.getHeight() != bh) {
                    e.bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                    e.canvas.setBitmap(e.bmp);
                }
                e.radius = radius; e.sat = sat; e.lift = lift;
            }
            if (e.stamp != rawStamp) {
                // copy the fresh raw capture into this recipe's bitmap, then
                // blur in place (no per-frame allocation). Only the sampled
                // bands are blurred — the rest is never read by any pane.
                e.canvas.drawBitmap(raw, 0, 0, null);
                int blurH = e.bmp.getHeight();
                for (int i = 0; i < bands.size(); i++) {
                    float[] band = bands.get(i);
                    int top = Math.max(0, (int) band[0]);
                    int bottom = Math.min(blurH - 1, (int) band[1]);
                    // a band can fall fully outside the capture (a pane mid-
                    // animation off the root's edge) — clamping both ends
                    // independently can then invert the range, and
                    // StackBlur's getPixels(height < 0) throws
                    // IllegalArgumentException. Skip empty ranges.
                    if (bottom < top) continue;
                    StackBlur.process(e.bmp, e.radius, e.sat, e.lift,
                        top, bottom);
                }
                e.stamp = rawStamp;
            }
            return e.bmp;
        }

        /** Re-draw the capture root into the shared scratch bitmap, clipped
         *  to the vertical bands the visible panes actually sample. Panes
         *  cluster at the top veil and the bottom nav pill, so the merged
         *  bands cover a small fraction of the root — rasterizing only
         *  them (instead of the whole pager) is a multi-× cut on capture
         *  cost, which dominates at the 1/4 capture size. */
        private void drawRaw() {
            // re-entrant call from a nested pane's onDraw (see `recording`)
            if (recording) return;
            root.getLocationOnScreen(rootLoc);
            int scrollX = root.getScrollX(), scrollY = root.getScrollY();
            bands.clear();
            for (int i = 0; i < views.size(); i++) {
                RealtimeBlurView v = views.get(i).get();
                if (v == null) { views.remove(i--); continue; }
                if (!v.isAttachedToWindow() || v.getVisibility() != VISIBLE
                    || v.getWidth() == 0 || v.getHeight() == 0
                    || v.frostScale <= 0f) continue;
                v.getLocationOnScreen(HERE);
                int m = v.pad + 1;
                addBand((HERE[1] - rootLoc[1]) / (float) FACTOR - m,
                        (HERE[1] - rootLoc[1] + v.getHeight()) / (float) FACTOR + m);
            }
            if (bands.isEmpty()) return;
            raw.eraseColor(fill);
            int rootW = root.getWidth();
            // ONE traversal for all bands: a multi-rect Path clips to the
            // union of its rects natively (UNION Region.Op is banned on P+,
            // but path-clip union is not — this is the standard workaround).
            // Traversal dispatch (view walks, draw methods, quickReject) is
            // the dominant capture cost, so halving the traversals from one
            // per band to one total matters more than the pixel fill.
            // Coordinates: same convention as the GPU record() — the manual
            // -scroll translate below puts the capture in viewport coords.
            bandPath.reset();
            for (int i = 0; i < bands.size(); i++) {
                float[] band = bands.get(i);
                bandPath.addRect(scrollX - 8, band[0] * FACTOR + scrollY,
                    scrollX + rootW + 8, band[1] * FACTOR + scrollY,
                    android.graphics.Path.Direction.CW);
            }
            rawCanvas.save();
            rawCanvas.scale(1f / FACTOR, 1f / FACTOR);
            rawCanvas.translate(-scrollX, -scrollY);
            rawCanvas.clipPath(bandPath);
            recording = true;
            try {
                root.draw(rawCanvas);
            } finally {
                recording = false;
            }
            rawCanvas.restore();
        }

        /** Insert [top, bottom] into the band list (sorted, merged on
         *  overlap) so each band is drawn with a single INTERSECT clip. */
        private void addBand(float top, float bottom) {
            for (int i = 0; i < bands.size(); i++) {
                float[] b = bands.get(i);
                if (top <= b[1] && bottom >= b[0]) {
                    b[0] = Math.min(b[0], top);
                    b[1] = Math.max(b[1], bottom);
                    while (i + 1 < bands.size() && bands.get(i + 1)[0] <= b[1]) {
                        b[1] = Math.max(b[1], bands.get(i + 1)[1]);
                        bands.remove(i + 1);
                    }
                    return;
                }
                if (top < b[0]) { bands.add(i, new float[]{top, bottom}); return; }
            }
            bands.add(new float[]{top, bottom});
        }
    }

    /** Classic stack blur with correct alpha handling: pixels are
     *  premultiplied before the box passes and unpremultiplied after, so
     *  semi-transparent source pixels blur into lighter tints instead of
     *  black-fringed halos. sat > 1 pushes the chroma of the result up
     *  (liquid glass): applied on the unpremultiplied values around the
     *  luma, which keeps hue and brightness relationships intact. */
    private static final class StackBlur {
        /** reusable scratch planes, grown to the largest bitmap seen. The
         *  blur runs on the UI thread every frame, so allocating ~5 MB of
         *  int[] per frame at the 1/4 capture size would trigger a GC storm
         *  (measured: p50 36ms) — shared grow-only buffers keep the steady
         *  state allocation-free. Single-threaded, so sharing is safe. */
        static int[] sPix, sA, sR, sG, sB, sOrig;

        static int[] grow(int[] arr, int n) {
            return arr == null || arr.length < n ? new int[n] : arr;
        }

        /** Box-blur rows [y0, y1] of the bitmap in place (rows outside the
         *  band are left untouched). The vertical pass reads radius rows
         *  beyond the band, so the fresh region is the band ± radius; the
         *  caller guarantees those rows contain valid capture content
         *  (fill or drawn) — bands are clipped with a blur margin. */
        static void process(Bitmap bmp, float radiusF, float sat, int lift,
                            int y0, int y1) {
            int w = bmp.getWidth(), h = bmp.getHeight();
            // defensive: an inverted or fully off-screen band must not
            // reach getPixels with a negative row count
            if (y1 < y0 || y0 >= h || y1 < 0) return;
            int radius = Math.max(1, Math.round(radiusF));
            // fractional radii below 1 can't drive the integer sliding
            // window: blur with radius 1, then blend each pixel back toward
            // the sharp original by the fraction (0 = no blur, 1 = full)
            float blend = radiusF < 1f ? radiusF : 1f;
            int y0r = Math.max(0, y0 - radius), y1r = Math.min(h - 1, y1 + radius);
            int rows = y1r - y0r + 1;
            int n = w * h;
            int[] pix = grow(sPix, n); sPix = pix;
            bmp.getPixels(pix, 0, w, 0, y0r, w, rows);
            int div = radius * 2 + 1;
            int[] a = grow(sA, n); sA = a;
            int[] r = grow(sR, n); sR = r;
            int[] g = grow(sG, n); sG = g;
            int[] b = grow(sB, n); sB = b;
            int[] orig = null;
            if (blend < 1f) {
                orig = grow(sOrig, n); sOrig = orig;
                System.arraycopy(pix, 0, orig, 0, rows * w);
            }

            // premultiply: transparent pixels must carry no color weight
            for (int i = 0; i < rows * w; i++) {
                int p = pix[i];
                int al = p >>> 24;
                a[i] = al;
                if (al == 255) {
                    r[i] = (p >> 16) & 0xFF; g[i] = (p >> 8) & 0xFF; b[i] = p & 0xFF;
                } else if (al == 0) {
                    r[i] = 0; g[i] = 0; b[i] = 0;
                } else {
                    r[i] = ((p >> 16) & 0xFF) * al / 255;
                    g[i] = ((p >> 8) & 0xFF) * al / 255;
                    b[i] = (p & 0xFF) * al / 255;
                }
            }

            // horizontal box pass (sliding window, four channels)
            for (int y = 0; y < rows; y++) {
                int row = y * w;
                int sa = 0, sr = 0, sg = 0, sb = 0;
                for (int i = -radius; i <= radius; i++) {
                    int idx = row + Math.min(w - 1, Math.max(0, i));
                    sa += a[idx]; sr += r[idx]; sg += g[idx]; sb += b[idx];
                }
                for (int x = 0; x < w; x++) {
                    a[row + x] = sa / div; r[row + x] = sr / div;
                    g[row + x] = sg / div; b[row + x] = sb / div;
                    int out = row + Math.max(0, x - radius);
                    int in = row + Math.min(w - 1, x + radius + 1);
                    sa += a[in] - a[out]; sr += r[in] - r[out];
                    sg += g[in] - g[out]; sb += b[in] - b[out];
                }
            }

            // vertical box pass (direct window sum per pixel; the bitmap is
            // tiny so the extra work is negligible) + unpremultiply — band
            // rows only. The window clamps at the fresh-region edges: when
            // the band sits at the bitmap's own top/bottom (nav pill at the
            // screen edge) the margin is truncated, and reads must clamp to
            // the loaded rows like the full-bitmap version did.
            int ly0 = Math.max(0, y0 - y0r), ly1 = Math.min(rows - 1, y1 - y0r);
            for (int x = 0; x < w; x++) {
                for (int y = ly0; y <= ly1; y++) {
                    int sa = 0, sr = 0, sg = 0, sb = 0;
                    for (int i = -radius; i <= radius; i++) {
                        int yy = y + i;
                        if (yy < 0) yy = 0; else if (yy > rows - 1) yy = rows - 1;
                        int idx = yy * w + x;
                        sa += a[idx]; sr += r[idx]; sg += g[idx]; sb += b[idx];
                    }
                    int A = sa / div;
                    int idx = y * w + x;
                    if (A <= 0) {
                        pix[idx] = 0;
                    } else {
                        int R = Math.min(255, (sr / div) * 255 / A);
                        int G = Math.min(255, (sg / div) * 255 / A);
                        int B = Math.min(255, (sb / div) * 255 / A);
                        if (sat > 1f) {
                            int lum = (int) (R * 0.299f + G * 0.587f + B * 0.114f);
                            R = Math.max(0, Math.min(255,
                                lum + (int) ((R - lum) * sat)));
                            G = Math.max(0, Math.min(255,
                                lum + (int) ((G - lum) * sat)));
                            B = Math.max(0, Math.min(255,
                                lum + (int) ((B - lum) * sat)));
                            // glass also reads a touch brighter than the matte
                            // page behind it — a small uniform lift sells the
                            // pane (light mode; dark mode passes 0 so the
                            // feathered boundary stays invisible at rest)
                            R = Math.min(255, R + lift);
                            G = Math.min(255, G + lift);
                            B = Math.min(255, B + lift);
                        }
                        pix[idx] = (A << 24) | (R << 16) | (G << 8) | B;
                    }
                    if (orig != null) {
                        // fractional radius: lerp the blurred pixel back
                        // toward the sharp capture by the radius fraction
                        int o = orig[idx], p = pix[idx];
                        int oa = o >>> 24, orr = (o >> 16) & 0xFF,
                            og = (o >> 8) & 0xFF, ob = o & 0xFF;
                        int pa = p >>> 24, pr = (p >> 16) & 0xFF,
                            pg = (p >> 8) & 0xFF, pb = p & 0xFF;
                        pix[idx] =
                            ((int) (oa + (pa - oa) * blend) << 24)
                          | ((int) (orr + (pr - orr) * blend) << 16)
                          | ((int) (og + (pg - og) * blend) << 8)
                          | (int) (ob + (pb - ob) * blend);
                    }
                }
            }
            bmp.setPixels(pix, 0, w, 0, y0r, w, rows);
        }
    }
}
