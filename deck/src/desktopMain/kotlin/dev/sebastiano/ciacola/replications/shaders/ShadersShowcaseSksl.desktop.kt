package dev.sebastiano.ciacola.replications.shaders

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import dev.sebastiano.ciacola.inspector.stageInteractive

internal object ShadersShowcaseSkslSources {

    /**
     * S05 (`shaders.trippyKaleidoscope`): 6-fold polar psychedelic kaleidoscope flower pulsing
     * radially outward across warm golden-lotus and electric magenta/violet/cobalt palettes.
     */
    val TrippyKaleidoscope: String = """
        uniform float2 iResolution;
        uniform float iTime;
        uniform float2 uOffset;
        uniform float uFolds;

        float2 rot2(float2 p, float a) {
            float c = cos(a);
            float s = sin(a);
            return float2(p.x * c - p.y * s, p.x * s + p.y * c);
        }

        float3 psychedelicPalette(float t, float warmth) {
            // Smoothly morphs between warm gold/amber/magenta (lotus) and electric violet/cobalt/magenta
            float3 warmA = float3(0.78, 0.46, 0.30);
            float3 warmB = float3(0.42, 0.44, 0.34);
            float3 warmC = float3(1.0, 1.0, 1.0);
            float3 warmD = float3(0.01, 0.14, 0.52);
            float3 warmCol = warmA + warmB * cos(6.2831853 * (warmC * t + warmD));

            float3 coolA = float3(0.46, 0.22, 0.70);
            float3 coolB = float3(0.48, 0.36, 0.42);
            float3 coolC = float3(1.0, 1.0, 1.0);
            float3 coolD = float3(0.68, 0.85, 0.14);
            float3 coolCol = coolA + coolB * cos(6.2831853 * (coolC * t + coolD));

            return mix(coolCol, warmCol, warmth);
        }

        half4 main(float2 fragCoord) {
            float minDim = max(min(iResolution.x, iResolution.y), 1.0);
            float2 uv = (fragCoord - 0.5 * iResolution) / minDim * 2.0 + uOffset;

            float r0 = length(uv);
            float a0 = atan(uv.y, uv.x);

            float folds = max(floor(uFolds + 0.5), 3.0);
            float sector = 6.2831853 / folds;

            // Seamless phase morph between warm lotus (0..pi) and violet mandala (pi..2pi)
            float warmth = 0.5 + 0.5 * cos(iTime);
            float twist = 0.24 * sin(iTime) + 0.28 * sin(iTime * 2.0) * smoothstep(0.0, 1.5, r0);
            float aFold = mod(a0 + twist + 6.2831853, sector);
            aFold = abs(aFold - 0.5 * sector);

            // Normalized mirror-wedge coordinate in [0..pi] so every fold count sculpts N distinct petals
            float wedge = aFold * folds;
            float petalCos = cos(wedge);
            float petalTip = 0.5 + 0.5 * petalCos;
            float rPetal = r0 / (1.0 + 0.30 * petalCos);

            // Preserve full wedge angular detail across all fold counts (3..12)
            float aNorm = aFold * (folds / 6.0);
            float2 kp = float2(cos(aNorm), sin(aNorm)) * rPetal;
            float3 accum = float3(0.0);

            // Radiant N-petal lotus bloom with unmistakable radial fold symmetry
            float cycle = iTime / 6.2831853;
            float petalWave = sin(rPetal * 6.5 - iTime * 2.0 + petalCos * 1.8);
            float lobeMask = smoothstep(-0.30, 0.88, petalWave * 0.55 + petalCos * 0.55);
            float outerPetal = smoothstep(0.08, 0.85, petalTip) * exp(-abs(rPetal - 0.72) * 2.2);
            accum += psychedelicPalette(rPetal * 0.52 - cycle, warmth) * (lobeMask * 0.48 + outerPetal * 0.52);

            // 4-octave recursive polar crystal fold driven by petal-warped coordinates (exact 2*pi loop)
            float2 p = kp;
            for (int i = 0; i < 4; i++) {
                float fi = float(i);
                p = rot2(p, sin(iTime) * 0.42 + fi * 0.45);
                p = abs(p) * 1.42 - float2(0.46 + 0.08 * sin(iTime + fi), 0.30 + 0.06 * cos(iTime * 2.0));
                float d = length(p) * exp(-rPetal * 0.52);
                float ring = abs(sin(d * 7.2 - iTime * 2.0 + fi * 0.7) / 7.2);
                float glow = pow(0.021 / max(ring, 0.008), 1.15);
                float3 layerCol = psychedelicPalette(rPetal * 0.46 + fi * 0.25 - cycle, warmth);
                accum += layerCol * glow * (0.24 / (1.0 + fi * 0.35)) * (0.55 + 0.45 * petalTip);
            }

            // Luminous golden/cyan neon filament ribs tracing each of the N petals & valley seams
            float seamRib = exp(-abs(sin(wedge)) * 11.0) * smoothstep(1.55, 0.10, r0);
            float petalRim = smoothstep(0.16, 0.01, abs(sin(rPetal * 8.0 - iTime * 3.0 + petalCos * 1.6))) * petalTip * smoothstep(1.5, 0.18, r0);
            float3 filamentCol = mix(float3(0.45, 0.88, 1.0), float3(1.0, 0.90, 0.28), warmth);
            accum += filamentCol * (seamRib * 0.42 + petalRim * 0.46);

            // Radiant N-pointed central starburst flower core
            float core = exp(-rPetal * 4.4) * (0.78 + 0.38 * petalCos);
            float3 coreCol = mix(float3(0.95, 0.82, 1.0), float3(1.0, 0.96, 0.68), warmth);
            accum += coreCol * core * 1.15;

            // Soft tonemap to keep highlights creamy and vibrant without harsh clipping
            float3 col = 1.0 - exp(-accum * 1.15);
            return half4(clamp(col, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    /**
     * S06 (`shaders.journeyDunes`): *Journey*-inspired sunset desert dunes with shimmering sunlit
     * crests, twinkling sand micro-facets, split-peak glowing mountain, silhouetted traveler with
     * flowing scarlet scarf, and gliding sky cloth ribbons.
     */
    val JourneyDunes: String = """
        uniform float2 iResolution;
        uniform float iTime;
        uniform float2 uDrift;
        uniform float uShimmer;

        float hash21(float2 p) {
            float3 p3 = fract(float3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        float valueNoise(float2 p) {
            float2 i = floor(p);
            float2 f = fract(p);
            float2 u = f * f * (3.0 - 2.0 * f);
            float a = hash21(i);
            float b = hash21(i + float2(1.0, 0.0));
            float c = hash21(i + float2(0.0, 1.0));
            float d = hash21(i + float2(1.0, 1.0));
            return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
        }

        // Dune height curves in top-down UV space (y=0 top, y=1 bottom)
        float duneHeight(float x, float freq, float amp, float phase, float base) {
            return base
                - amp * sin(x * freq + phase)
                - amp * 0.38 * cos(x * freq * 1.85 - phase * 0.7);
        }

        // Distance to a 2D line segment (for cloak silhouette, scarf & sky ribbons)
        float sdSegment(float2 p, float2 a, float2 b) {
            float2 pa = p - a;
            float2 ba = b - a;
            float h = clamp(dot(pa, ba) / max(dot(ba, ba), 0.0001), 0.0, 1.0);
            return length(pa - ba * h);
        }

        half4 main(float2 fragCoord) {
            float2 res = max(iResolution, float2(1.0));
            float2 uv = fragCoord / res;
            float aspect = res.x / res.y;
            float2 pan = uDrift * 0.08;
            float x = uv.x + pan.x;
            float y = uv.y + pan.y;

            // --- 1. Golden Sunset Sky & Split-Peak Journey Mountain ---
            float2 sunPos = float2(0.50, 0.16);
            float2 sunDelta = float2((x - sunPos.x) * aspect, y - sunPos.y);
            float sunDist = length(sunDelta);

            float3 skyTop = float3(0.62, 0.49, 0.30);
            float3 skyMid = float3(0.87, 0.72, 0.46);
            float3 skyHor = float3(0.98, 0.87, 0.64);
            float3 col = mix(skyTop, skyMid, smoothstep(0.0, 0.35, y));
            col = mix(col, skyHor, smoothstep(0.24, 0.56, y));

            // Warm sun halo & soft glow
            float halo = exp(-sunDist * 3.4) * 0.62 + exp(-sunDist * 9.5) * 0.55;
            col += float3(1.0, 0.94, 0.76) * halo;

            // Iconic split-peak Journey mountain centered at x = 0.50
            float mx = abs(x - 0.50);
            float cleft = 0.014 * exp(-pow(mx / 0.012, 2.0));
            float mtnTop = 0.135 + mx * 1.65 + cleft;
            if (y > mtnTop && y < 0.58) {
                float mtnH = smoothstep(mtnTop, mtnTop + 0.006, y);
                float3 mtnBase = mix(float3(0.90, 0.77, 0.56), float3(0.76, 0.60, 0.42), smoothstep(0.0, 0.14, mx));
                // Atmospheric fade into golden haze near base
                mtnBase = mix(mtnBase, skyHor * 0.98, smoothstep(0.22, 0.48, y));
                col = mix(col, mtnBase, mtnH * 0.85);
            }

            // Vertical luminous beacon beam rising from the split mountain peak
            float beamX = abs(x - 0.50);
            float beamWidth = mix(0.0035, 0.010, smoothstep(0.16, 0.0, y));
            float beamPulse = 0.90 + 0.10 * sin(iTime * 2.0);
            float beam = exp(-pow(beamX / beamWidth, 2.0)) * smoothstep(0.32, 0.14, y) * smoothstep(0.0, 0.08, y);
            col += float3(1.0, 0.98, 0.88) * beam * 0.95 * beamPulse;

            // Soft sunlit horizon cumulus cloud bank (layered soft billows, no harsh rim outline)
            float cloudDrift = sin(iTime) * 0.015;
            float cProfile = 0.43
                - 0.048 * abs(sin((x + cloudDrift) * 9.5 + 0.4))
                - 0.028 * abs(cos((x - cloudDrift * 0.7) * 19.0 + 1.1))
                - 0.018 * valueNoise(float2((x + cloudDrift) * 14.0, 2.4));
            float cloudMask = smoothstep(cProfile - 0.012, cProfile + 0.024, y) * smoothstep(0.62, 0.48, y);
            float cloudTopLight = smoothstep(cProfile + 0.065, cProfile - 0.005, y);
            float3 cloudBase = mix(float3(0.86, 0.71, 0.50), float3(0.99, 0.92, 0.75), cloudTopLight);
            col = mix(col, cloudBase, cloudMask * 0.68);

            // --- 2. Layered Wind-Rippled Desert Dunes with Sunlit Crest Rims ---
            float sway = sin(iTime) * 0.012;
            float dFar = duneHeight(x + sway * 0.3, 6.2, 0.038, 0.6, 0.52);
            float dMid = duneHeight(x - sway * 0.5, 4.8, 0.068, 2.3, 0.58);
            float dNearLeft = 0.42 + 0.24 * pow(clamp(x * 1.65, 0.0, 1.0), 1.35) - 0.03 * sin(x * 7.0);
            float dNearRight = 0.68 - 0.19 * pow(clamp((x - 0.42) * 1.72, 0.0, 1.0), 1.25) + 0.018 * sin(x * 9.0 + 0.8);
            float dFore = min(dNearLeft, dNearRight) + 0.012 * sin(x * 5.0 + iTime);

            // Far dune ridge
            if (y >= dFar - 0.008) {
                float mask = smoothstep(dFar - 0.004, dFar + 0.004, y);
                float slope = cos((x + sway * 0.3) * 6.2 + 0.6);
                float3 shade = mix(float3(0.68, 0.45, 0.34), float3(0.90, 0.67, 0.45), smoothstep(-0.6, 0.8, slope));
                float rim = exp(-abs(y - dFar) * 130.0);
                col = mix(col, shade + float3(1.0, 0.90, 0.66) * rim * 0.55, mask);
            }

            // Mid central dune mound (like the twin-banner hill in Journey)
            float hillCenter = 0.64;
            float hillY = 0.49 + 0.22 * pow(abs(x - hillCenter) * 2.1, 1.65);
            float dMidCombined = min(dMid, hillY);
            if (y >= dMidCombined - 0.008) {
                float mask = smoothstep(dMidCombined - 0.004, dMidCombined + 0.004, y);
                float sideLight = smoothstep(0.76, 0.48, x);
                float3 midSand = mix(float3(0.64, 0.39, 0.32), float3(0.88, 0.60, 0.43), sideLight);
                float ripples = sin(x * 85.0 + y * 55.0 + sin(y * 18.0) * 1.5) * 0.5 + 0.5;
                midSand += float3(0.08, 0.05, 0.02) * ripples * smoothstep(dMidCombined, dMidCombined + 0.08, y);
                float rim = exp(-abs(y - dMidCombined) * 150.0);
                col = mix(col, midSand + float3(1.0, 0.93, 0.74) * rim * 0.68, mask);
            }

            // Foreground sculpted sand slopes with wind ripples & sparkling sand glitter
            if (y >= dFore - 0.010) {
                float mask = smoothstep(dFore - 0.004, dFore + 0.004, y);
                float depth = clamp((y - dFore) / max(1.0 - dFore, 0.01), 0.0, 1.0);
                // Sunlit valley floor in center, warm terracotta shadow on left/right slopes
                float sunPool = exp(-pow((x - 0.52) * 2.3, 2.0)) * (1.0 - depth * 0.45);
                float3 shadowSand = float3(0.55, 0.32, 0.29);
                float3 warmSand = float3(0.84, 0.53, 0.38);
                float3 brightSand = float3(0.99, 0.80, 0.54);
                float3 sand = mix(shadowSand, warmSand, smoothstep(0.05, 0.45, x) * smoothstep(0.98, 0.55, x));
                sand = mix(sand, brightSand, sunPool * 0.85);

                // Diagonal wind-carved sand ripples
                float ripplePhase = x * 34.0 + y * 52.0 + sin(x * 8.0 + y * 6.0) * 2.2;
                float rippleBand = 0.5 + 0.5 * sin(ripplePhase);
                float rippleFade = smoothstep(0.02, 0.26, depth) * smoothstep(0.95, 0.25, depth);
                sand = mix(sand * 0.88, sand * 1.10, rippleBand * rippleFade);

                // Glittering specular sand micro-facets twinkling organically in the foreground
                float2 grainPix = floor(fragCoord);
                float grainRand = hash21(grainPix + float2(19.3, 47.1));
                float grainRand2 = hash21(floor(fragCoord * 0.5) + float2(71.7, 13.9));
                float twinkle = pow(0.5 + 0.5 * sin(iTime * 2.0 + grainRand * 40.0 + x * 18.0), 12.0);
                float glitterZone = smoothstep(0.56, 0.94, y) * (0.40 + 0.60 * sunPool);
                float fineGlint = step(0.92, grainRand) * twinkle * glitterZone * uShimmer;
                float warmGlint = step(0.975, grainRand2) * pow(0.5 + 0.5 * cos(iTime * 2.0 + grainRand2 * 31.0), 8.0) * glitterZone * uShimmer;
                sand += float3(1.0, 0.95, 0.76) * (fineGlint * 1.10 + warmGlint * 0.85);

                // Crisp golden sunlit crest rim along the foreground dune ridge
                float crestRim = exp(-abs(y - dFore) * 165.0);
                sand += float3(1.0, 0.96, 0.82) * crestRim * 0.92;

                col = mix(col, sand, mask);
            }

            // --- 3. Silhouetted Journey Traveler & Flowing Scarlet Scarf (Left Foreground) ---
            float2 foot = float2(0.315, 0.91);
            // Soft cast ground shadow under traveler
            float2 shP = float2((x - 0.295) * 1.25, (y - 0.925) * 3.8);
            float shadow = smoothstep(0.095, 0.03, length(shP));
            col *= (1.0 - shadow * 0.48);

            // Flowing scarlet-and-gold scarf waving left from the traveler's shoulders
            if (x < 0.345 && y > 0.55 && y < 0.94) {
                float tScarf = clamp((0.34 - x) / 0.34, 0.0, 1.0);
                float scarfWave = 0.65 + tScarf * 0.21
                    + 0.026 * sin(tScarf * 7.5 - iTime * 2.0)
                    + 0.012 * cos(tScarf * 13.0 - iTime * 3.0);
                float scarfHalfH = mix(0.008, 0.016, tScarf);
                float scarfDist = abs(y - scarfWave) - scarfHalfH;
                float scarfMask = smoothstep(0.004, -0.002, scarfDist) * smoothstep(0.342, 0.325, x);
                float goldEdge = smoothstep(0.006, 0.001, abs(abs(y - scarfWave) - scarfHalfH * 0.78));
                float glyph = smoothstep(0.014, 0.004, abs(fract(tScarf * 4.5 - iTime * 0.1591549) - 0.5)) * 0.7;
                float3 scarfCol = mix(float3(0.76, 0.17, 0.09), float3(0.99, 0.76, 0.22), max(goldEdge, glyph * 0.65));
                col = mix(col, scarfCol, scarfMask);
            }

            // Cloaked traveler body (A-line crimson robe + pointed hood + slender legs)
            float leg1 = sdSegment(float2(x, y), float2(0.318, 0.81), float2(0.308, 0.925));
            float leg2 = sdSegment(float2(x, y), float2(0.326, 0.81), float2(0.314, 0.905));
            float legMask = smoothstep(0.0055, 0.002, min(leg1, leg2));
            col = mix(col, float3(0.19, 0.10, 0.09), legMask);

            if (y >= 0.53 && y <= 0.84) {
                float robeT = clamp((y - 0.56) / 0.27, 0.0, 1.0);
                float robeCenter = 0.338 - robeT * 0.028 + 0.004 * sin(iTime + robeT * 4.0);
                float robeHalfW = mix(0.013, 0.052, pow(robeT, 1.15));
                float inRobe = smoothstep(robeHalfW + 0.003, robeHalfW - 0.002, abs(x - robeCenter))
                    * smoothstep(0.555, 0.568, y)
                    * smoothstep(0.835, 0.822, y);
                // Pointed hood tip
                float hoodDist = length(float2((x - 0.337) * 1.4, (y - 0.552) * 0.9));
                float robeMask = max(inRobe, smoothstep(0.020, 0.014, hoodDist));
                float sideShade = smoothstep(robeCenter + robeHalfW, robeCenter - robeHalfW, x);
                float3 robeCol = mix(float3(0.42, 0.10, 0.08), float3(0.84, 0.24, 0.13), sideShade);
                // Embroidered golden hem trim
                float hemBand = smoothstep(0.014, 0.003, abs(y - 0.818));
                robeCol = mix(robeCol, float3(0.98, 0.78, 0.24), hemBand * 0.9);
                col = mix(col, robeCol, robeMask);
            }

            // --- 4. Two Gliding Scarlet Sky Cloth Ribbons ---
            // Upper-left flying cloth creature
            if (x < 0.33 && y < 0.42) {
                float tL = clamp((0.29 - x) / 0.30, 0.0, 1.0);
                float ribLY = 0.325 - tL * 0.29 + 0.025 * sin(tL * 5.0 - iTime * 2.0);
                float halfHL = mix(0.007, 0.022, smoothstep(0.0, 0.45, tL));
                float mL = smoothstep(halfHL + 0.004, halfHL - 0.002, abs(y - ribLY)) * smoothstep(0.305, 0.28, x);
                float headGlowL = exp(-length(float2((x - 0.272) * aspect, y - (0.325 + 0.006 * sin(iTime * 2.0)))) * 48.0);
                float3 ribColL = mix(float3(0.68, 0.18, 0.11), float3(0.96, 0.66, 0.22), smoothstep(0.004, 0.0, abs(abs(y - ribLY) - halfHL * 0.8)));
                col = mix(col, ribColL, mL * 0.92);
                col += float3(1.0, 0.90, 0.56) * headGlowL * 0.95;
            }

            // Upper-right dramatic foreground cloth ribbon (organically tapered head)
            if (x > 0.56 && y < 0.45) {
                float tR = clamp((x - 0.61) / 0.40, 0.0, 1.0);
                float ribRY = 0.22 - 0.14 * sin(tR * 1.9 + 0.3) + 0.022 * sin(tR * 4.0 + iTime * 2.0);
                float taperHead = smoothstep(0.0, 0.14, tR);
                float halfHR = mix(0.044, 0.018, tR) * taperHead;
                float mR = smoothstep(halfHR + 0.005, max(halfHR - 0.002, 0.0), abs(y - ribRY)) * smoothstep(0.0, 0.04, tR);
                float trimR = smoothstep(0.007, 0.002, abs(abs(y - ribRY) - halfHR * 0.80)) * taperHead;
                float3 ribColR = mix(float3(0.75, 0.18, 0.08), float3(0.98, 0.70, 0.20), trimR);
                float orbDist = length(float2((x - 0.675) * aspect, y - (0.215 + 0.012 * sin(iTime * 2.0))));
                float orb = smoothstep(0.036, 0.010, orbDist);
                ribColR = mix(ribColR, float3(1.0, 0.94, 0.72), orb * 0.85);
                col = mix(col, ribColR, mR * 0.96);
                col += float3(1.0, 0.90, 0.58) * exp(-orbDist * 42.0) * 0.45;
            }

            // Warm subtle atmospheric vignette
            float vig = 1.0 - 0.18 * dot(uv - 0.5, uv - 0.5);
            return half4(clamp(col * vig, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    /**
     * S09 (`shaders.raindropsWindow`): Moody dusk London rain-on-window SkSL shader refracting
     * blurred twilight sky & warm city bokeh lights through procedural falling & trailing drops.
     */
    val RaindropsWindow: String = """
        uniform float2 iResolution;
        uniform float iTime;
        uniform float2 uOffset;
        uniform float uIntensity;

        float hash12(float2 p) {
            float3 p3 = fract(float3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        // Analytic falling teardrop + micro-trail layer with zero grid-boundary discontinuities.
        // Returns float4(normalX, normalY, dropMask, clearedWake).
        float4 fallingDropLayer(float2 uv, float cols, float rows, float cycle, float speedMult, float seed) {
            float2 st = uv * float2(cols, rows);
            float colIdx = floor(st.x);
            float colRand = hash12(float2(colIdx + seed * 3.7, 17.3));

            // Integer cycles per loop period (>= 2) with wrapped row hash guarantee exact seamless wrap
            float colSpeed = max(floor(speedMult + 0.5), 2.0) + floor(colRand * 2.0);
            st.y -= cycle * colSpeed + colRand * 6.0;

            float wrappedRow = mod(mod(floor(st.y), colSpeed) + colSpeed, colSpeed);
            float2 id = float2(colIdx, wrappedRow);
            float2 f = fract(st) - 0.5;
            float n = hash12(id + float2(seed * 7.1, 3.9));
            float active = step(0.24, n);

            // Integer-harmonic meander bounded inside cell edges (periodic across integer colSpeed shift)
            float wig = sin(st.y * 6.2831853 + colRand * 6.2831853) * 0.085
                + sin(st.y * 12.5663706 + n * 6.2831853) * 0.03;
            float dropX = clamp((n - 0.5) * 0.34 + wig, -0.23, 0.23);
            float dropY = 0.14 * sin(cycle * 6.2831853 * colSpeed + n * 6.2831853);

            // Convex teardrop profile (wider bottom, slightly tapered top)
            float2 d = f - float2(dropX, dropY);
            float2 sd = float2(
                d.x * 1.18 * (1.0 + 0.26 * clamp(-d.y * 3.0, -0.35, 0.55)),
                d.y * 1.48
            );
            float dropR = 0.13 + 0.075 * fract(n * 13.7);
            float dist = length(sd);
            float dropMask = smoothstep(dropR, dropR * 0.10, dist) * active;
            // Smooth spherical dome normal (0 at border, strongest near rim)
            float2 dropNormal = (sd / max(dropR, 0.01)) * dropMask;

            // Micro trailing beads strictly above the falling drop, smoothly windowed before cell edges
            float above = dropY - f.y;
            float cellSafe = smoothstep(0.47, 0.34, abs(f.y)) * smoothstep(0.47, 0.30, abs(f.x));
            float trailEnv = smoothstep(0.03, 0.10, above) * smoothstep(0.55, 0.16, above) * cellSafe * active;
            float trailPhase = fract(f.y * 5.0) - 0.5;
            float2 td = float2((f.x - dropX) * 1.8, trailPhase * 0.34);
            float trailR = 0.105;
            float trailMask = smoothstep(trailR, trailR * 0.18, length(td)) * trailEnv * 0.72;
            float2 trailNormal = (td / trailR) * trailMask * 0.65;

            float wake = smoothstep(0.14, 0.03, abs(f.x - dropX)) * trailEnv;
            return float4(dropNormal + trailNormal, max(dropMask, trailMask), wake);
        }

        // Analytic static condensation micro-beads strictly bounded inside each cell (zero seams).
        // Returns float3(normalX, normalY, mask).
        float3 staticBeadsLayer(float2 uv, float scale, float seed) {
            float2 st = uv * scale;
            float2 id = floor(st);
            float2 f = fract(st) - 0.5;
            float n = hash12(id + float2(seed * 19.3, 41.7));
            float active = step(0.32, n);
            float r = 0.085 + 0.115 * fract(n * 19.1);
            float maxOff = max(0.44 - r, 0.02);
            float2 off = (float2(fract(n * 7.31), fract(n * 37.3)) - 0.5) * (2.0 * maxOff);
            float2 d = f - off;
            float mask = smoothstep(r, r * 0.16, length(d)) * active;
            float2 norm = (d / max(r, 0.01)) * mask;
            return float3(norm, mask);
        }

        // Procedural dusk London backdrop with steel-lavender twilight sky & warm city bokeh lights
        float3 duskCityBackdrop(float2 uv, float sharpness) {
            float3 skyTop = float3(0.44, 0.50, 0.66);
            float3 skyMid = float3(0.66, 0.72, 0.89);
            float3 horizon = float3(0.24, 0.22, 0.29);
            float3 street = float3(0.30, 0.34, 0.45);

            float3 col = mix(skyTop, skyMid, smoothstep(0.0, 0.34, uv.y) * smoothstep(0.68, 0.28, uv.y));
            col = mix(col, horizon, smoothstep(0.40, 0.58, uv.y));
            col = mix(col, street, smoothstep(0.64, 0.96, uv.y));

            // Soft dusk architectural silhouettes
            float bldg1 = smoothstep(0.24, 0.14, abs(uv.x - 0.14)) * smoothstep(0.66, 0.08, uv.y);
            float bldg2 = smoothstep(0.18, 0.08, abs(uv.x - 0.84)) * smoothstep(0.75, 0.16, uv.y);
            col = mix(col, float3(0.20, 0.19, 0.25), (bldg1 * 0.46 + bldg2 * 0.44));

            // Warm blurred city streetlamp, crimson brake-light & shop-window bokeh orbs
            float blurScale = mix(1.0, 0.54, sharpness);
            float bWarm1 = exp(-pow(length((uv - float2(0.22, 0.62)) * float2(1.5, 1.0)) / (0.14 * blurScale), 2.0));
            float bWarm2 = exp(-pow(length((uv - float2(0.70, 0.57)) * float2(1.5, 1.0)) / (0.13 * blurScale), 2.0));
            float bRed1  = exp(-pow(length((uv - float2(0.44, 0.66)) * float2(1.6, 1.0)) / (0.11 * blurScale), 2.0));
            float bCool1 = exp(-pow(length((uv - float2(0.48, 0.26)) * float2(1.2, 1.0)) / (0.24 * blurScale), 2.0));
            float bGold  = exp(-pow(length((uv - float2(0.85, 0.68)) * float2(1.5, 1.0)) / (0.10 * blurScale), 2.0));

            col += float3(1.00, 0.72, 0.36) * bWarm1 * (0.36 + 0.22 * sharpness);
            col += float3(0.98, 0.64, 0.30) * bWarm2 * (0.34 + 0.22 * sharpness);
            col += float3(0.92, 0.28, 0.28) * bRed1  * (0.30 + 0.18 * sharpness);
            col += float3(0.78, 0.86, 1.00) * bCool1 * (0.30 + 0.12 * sharpness);
            col += float3(1.00, 0.82, 0.46) * bGold  * (0.28 + 0.18 * sharpness);
            return col;
        }

        half4 main(float2 fragCoord) {
            float2 res = max(iResolution, float2(1.0));
            float2 uv = fragCoord / res;
            float aspect = res.x / res.y;
            float cycle = iTime / 6.2831853;

            float2 auv = float2((uv.x + uOffset.x * 0.06) * aspect, uv.y + uOffset.y * 0.06);

            // 2 parallax falling teardrop layers + 2 static condensation mist layers
            float4 f1 = fallingDropLayer(auv, 5.5, 2.8, cycle, 2.0, 1.0);
            float4 f2 = fallingDropLayer(auv + float2(0.41, 0.23), 9.0, 4.5, cycle, 3.0, 5.0);
            float3 s1 = staticBeadsLayer(auv, 16.0, 1.0);
            float3 s2 = staticBeadsLayer(auv + float2(0.37, 0.19), 28.0, 3.0);

            float wakeClear = clamp(f1.w + f2.w, 0.0, 1.0);
            float staticScale = 1.0 - wakeClear * 0.82;

            float2 dropNormal = f1.xy * 1.10 + f2.xy * 0.78
                + (s1.xy * 0.52 + s2.xy * 0.34) * staticScale;
            float dropMask = clamp(
                max(max(f1.z, f2.z * 0.88), max(s1.z * 0.75, s2.z * 0.55) * staticScale),
                0.0,
                1.0
            );

            // Convex water droplets invert and focus the dusk cityscape behind the glass
            float2 refractedUv = clamp(uv - dropNormal * (0.095 * uIntensity), float2(0.0), float2(1.0));
            float3 mistyPane = duskCityBackdrop(uv, 0.08);
            float3 clearWake = duskCityBackdrop(uv, 0.36);
            float3 glassBase = mix(mistyPane, clearWake, wakeClear * 0.55);
            float3 sharpRefract = duskCityBackdrop(refractedUv, 0.96);
            float3 col = mix(glassBase, sharpRefract, smoothstep(0.04, 0.45, dropMask));

            // Natural glass meniscus rim lighting: dark upper crescent + crisp glowing lower edge
            float normalLen = length(dropNormal);
            float lowerRim = clamp(dropNormal.y * 1.35, 0.0, 1.0) * dropMask;
            float upperShadow = clamp(-dropNormal.y * 1.25, 0.0, 1.0) * dropMask;
            float specDot = pow(clamp(dot(normalize(dropNormal + float2(0.0001)), float2(-0.45, -0.89)), 0.0, 1.0), 8.0)
                * smoothstep(0.25, 0.80, normalLen) * dropMask;
            col *= (1.0 - upperShadow * 0.46);
            col += float3(0.90, 0.95, 1.00) * (lowerRim * 0.48 + specDot * 0.55);

            // Subtle atmospheric vignette framing the wet window pane
            float2 vigP = uv - 0.5;
            float vig = 1.0 - 0.34 * dot(vigP, vigP);
            return half4(clamp(col * vig, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    /**
     * S45–S46 (`shaders.plaidHyperspaceStarfield`): Full-bleed *Spaceballs* "They've gone to
     * plaid!" square perspective tartan warp-tunnel rushing outward from a glowing golden starburst
     * blended with a rich golden cosmic starfield nebula (`uPlaidMix` default `0.65`).
     */
    val PlaidHyperspaceStarfield: String = """
        uniform float2 iResolution;
        uniform float iTime;
        uniform float uPlaidMix;

        float hash21(float2 p) {
            p = fract(p * float2(234.34, 435.345));
            p += dot(p, p + 34.23);
            return fract(p.x * p.y);
        }

        float starLayer(float2 uv, float scale, float2 cellVelocity, float cycle, float phase, float2 seedOffset) {
            float2 st = uv * scale + seedOffset + cellVelocity * cycle;
            float2 gv = fract(st) - 0.5;
            float2 wrapPeriod = max(abs(cellVelocity), float2(1.0));
            float2 id = mod(mod(floor(st), wrapPeriod) + wrapPeriod, wrapPeriod);
            float rnd = hash21(id + seedOffset);
            float2 off = (float2(rnd, fract(rnd * 31.7)) - 0.5) * 0.72;
            float d = length(gv - off);
            float sz = 0.014 + 0.032 * fract(rnd * 17.3);
            float twinkle = 0.60 + 0.40 * sin(phase + rnd * 6.2831853);
            float core = smoothstep(sz, 0.0, d) * twinkle;
            float halo = exp(-d * 14.0) * 0.34 * twinkle;
            return core + halo;
        }

        // Authentic Scottish tartan stripe profile on periodic coord t in [0, 1)
        float3 tartanBand(float t) {
            float x = fract(t);
            // Warm amber-gold twin stripes
            float gold1 = smoothstep(0.055, 0.025, abs(x - 0.10));
            float gold2 = smoothstep(0.042, 0.018, abs(x - 0.21));
            // Crimson-red twin warp beams
            float red1  = smoothstep(0.062, 0.026, abs(x - 0.44));
            float red2  = smoothstep(0.048, 0.020, abs(x - 0.57));
            // Deep royal-blue / indigo accent stripe
            float blue1 = smoothstep(0.068, 0.028, abs(x - 0.78));
            // Fine bright cream-gold thread highlight
            float thread = smoothstep(0.018, 0.006, abs(x - 0.90));

            float3 c = float3(0.0);
            c += float3(1.00, 0.68, 0.16) * (gold1 + gold2 * 0.88);
            c += float3(0.92, 0.10, 0.08) * (red1 + red2 * 0.85);
            c += float3(0.16, 0.18, 0.78) * blue1;
            c += float3(0.98, 0.90, 0.72) * thread * 0.75;
            return c;
        }

        half4 main(float2 fragCoord) {
            float2 res = max(iResolution, float2(1.0));
            float minDim = max(min(res.x, res.y), 1.0);
            float2 p = (fragCoord - 0.5 * res) / minDim;
            float cycle = iTime / 6.2831853;
            float dist = length(p);

            // --- 1. Golden Cosmic Starfield Nebula ---
            float3 cosmos = float3(0.04, 0.025, 0.012);
            float neb1 = 0.5 + 0.5 * sin(p.x * 5.2 + sin(p.y * 4.1 + iTime) + iTime);
            float neb2 = 0.5 + 0.5 * cos((p.x - p.y) * 7.5 - iTime);
            float neb3 = 0.5 + 0.5 * sin(dist * 6.0 - iTime * 2.0 + atan(p.y, p.x) * 3.0);
            float3 dust = mix(float3(0.32, 0.19, 0.06), float3(0.72, 0.52, 0.22), neb1 * 0.65 + neb2 * 0.35);
            cosmos += dust * (0.36 + 0.24 * neb3);

            // Integer cell shifts per loop with periodic cell IDs guarantee exact seamless starfield wrap
            float s1 = starLayer(p, 14.0, float2(3.0, 2.0), cycle, iTime, float2(0.0, 0.0));
            float s2 = starLayer(p, 20.0, float2(-5.0, 3.0), cycle, iTime * 2.0, float2(3.1, 1.7));
            float s3 = starLayer(p, 28.0, float2(7.0, -4.0), cycle, iTime * 3.0, float2(7.4, 4.9));
            cosmos += float3(1.00, 0.94, 0.78) * s1 * 0.95;
            cosmos += float3(0.95, 0.80, 0.52) * s2 * 0.72;
            cosmos += float3(0.84, 0.90, 1.00) * s3 * 0.55;

            // --- 2. Spaceballs Square Perspective Tartan Plaid Warp-Tunnel ---
            float ax = abs(p.x);
            float ay = abs(p.y);
            float cheb = max(ax, ay);

            // Perspective depth rushing continuously toward the viewer (4.0 * 0.5 = 2.0 exact bands/loop)
            float zDepth = 0.72 / max(cheb, 0.0025) - cycle * 4.0;
            // Lateral coordinate along each of the 4 square tunnel walls
            float wallU = (ax > ay) ? (p.y / max(ax, 0.001)) : (p.x / max(ay, 0.001));
            float lateral = wallU * 0.5 + 0.5;

            float3 radialStripes = tartanBand(lateral * 1.5 + 0.04 * sin(iTime));
            float3 depthRings = tartanBand(zDepth * 0.5);

            // Woven tartan combination + bright luminous intersections
            float3 weave = radialStripes * 0.78 + depthRings * 0.62 + (radialStripes * depthRings) * 1.45;
            // Subtle dark obsidian tunnel void in unlit tartan squares + perspective depth attenuation
            float depthAtten = smoothstep(0.015, 0.14, cheb) * (0.42 + 0.58 * clamp(cheb * 1.65, 0.0, 1.0));
            float3 plaid = float3(0.03, 0.015, 0.008) + weave * depthAtten;

            // Subtle lateral starfield framing at wide-aspect edges when uPlaidMix is moderate
            float mixAmt = clamp(uPlaidMix, 0.0, 1.0);
            float edgeFrame = smoothstep(0.88, 0.62, ax) * mix(1.0, 1.0, mixAmt);
            float effectivePlaid = mixAmt * mix(edgeFrame, 1.0, smoothstep(0.75, 1.0, mixAmt));
            float3 col = mix(cosmos, plaid, effectivePlaid);

            // --- 3. Radiant Central Golden Sunburst Star ---
            float angle = atan(p.y, p.x);
            float rays = 0.65 + 0.35 * pow(0.5 + 0.5 * cos(angle * 16.0 + iTime), 3.0);
            float starCore = exp(-dist * 16.5) * 1.55;
            float starCorona = exp(-dist * 5.8) * 0.68 * rays;
            col += float3(1.00, 0.96, 0.84) * starCore + float3(1.00, 0.74, 0.28) * starCorona;

            return half4(clamp(col, 0.0, 1.0), 1.0);
        }
    """.trimIndent()
}

/**
 * Specimen #1 (`shaders.trippyKaleidoscope`, `periodMs = 12_000`, S05):
 * Vibrant 6-fold psychedelic polar kaleidoscope SkSL `RuntimeEffect` shader.
 */
@Composable
fun TrippyKaleidoscopeSpecimen(
    loopMillis: Long,
    isInteractive: Boolean = false,
    modifier: Modifier = Modifier,
    folds: Float = 6f,
) {
    val iTime = loopMillisToRadians(loopMillis, ShadersSpecimenIds.TRIPPY_KALEIDOSCOPE_PERIOD_MS)
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }

    val interactiveModifier = if (isInteractive) {
        modifier
            .stageInteractive(ShadersSpecimenIds.TRIPPY_KALEIDOSCOPE, "Trippy Kaleidoscope")
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    dragX = (dragX + dragAmount.x / 320f).coerceIn(-0.45f, 0.45f)
                    dragY = (dragY + dragAmount.y / 320f).coerceIn(-0.45f, 0.45f)
                }
            }
    } else {
        modifier
    }

    TimeVec2ParamSkslBox(
        sksl = ShadersShowcaseSkslSources.TrippyKaleidoscope,
        iTime = iTime,
        vec2Name = "uOffset",
        x = if (isInteractive) dragX else 0f,
        y = if (isInteractive) dragY else 0f,
        paramName = "uFolds",
        paramValue = folds.coerceIn(3f, 12f),
        modifier = interactiveModifier,
    )
}

/**
 * Specimen #2 (`shaders.journeyDunes`, `periodMs = 14_000`, S06):
 * Warm *Journey*-inspired desert sand-dunes SkSL shader with glittering crests, split-peak glowing
 * mountain, silhouetted traveler, and flowing scarlet ribbons.
 */
@Composable
fun JourneyDunesSpecimen(
    loopMillis: Long,
    isInteractive: Boolean = false,
    modifier: Modifier = Modifier,
    shimmer: Float = 1f,
) {
    val iTime = loopMillisToRadians(loopMillis, ShadersSpecimenIds.JOURNEY_DUNES_PERIOD_MS)
    var driftX by remember { mutableFloatStateOf(0f) }
    var driftY by remember { mutableFloatStateOf(0f) }

    val interactiveModifier = if (isInteractive) {
        modifier
            .stageInteractive(ShadersSpecimenIds.JOURNEY_DUNES, "Journey Dunes")
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    driftX = (driftX + dragAmount.x / 420f).coerceIn(-0.6f, 0.6f)
                    driftY = (driftY + dragAmount.y / 420f).coerceIn(-0.3f, 0.3f)
                }
            }
    } else {
        modifier
    }

    TimeVec2ParamSkslBox(
        sksl = ShadersShowcaseSkslSources.JourneyDunes,
        iTime = iTime,
        vec2Name = "uDrift",
        x = if (isInteractive) driftX else 0f,
        y = if (isInteractive) driftY else 0f,
        paramName = "uShimmer",
        paramValue = shimmer.coerceIn(0f, 2f),
        modifier = interactiveModifier,
    )
}

/**
 * Specimen #5 (`shaders.raindropsWindow`, `periodMs = 10_000`, S09):
 * Atmospheric rainy window SkSL shader refracting dusk twilight sky & warm blurred city bokeh
 * lights through procedural falling and trailing raindrops.
 */
@Composable
fun RaindropsWindowSpecimen(
    loopMillis: Long,
    isInteractive: Boolean = false,
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
) {
    val iTime = loopMillisToRadians(loopMillis, ShadersSpecimenIds.RAINDROPS_WINDOW_PERIOD_MS)
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val interactiveModifier = if (isInteractive) {
        modifier
            .stageInteractive(ShadersSpecimenIds.RAINDROPS_WINDOW, "Raindrops Window")
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX = (offsetX + dragAmount.x / 360f).coerceIn(-0.8f, 0.8f)
                    offsetY = (offsetY + dragAmount.y / 360f).coerceIn(-0.8f, 0.8f)
                }
            }
    } else {
        modifier
    }

    TimeVec2ParamSkslBox(
        sksl = ShadersShowcaseSkslSources.RaindropsWindow,
        iTime = iTime,
        vec2Name = "uOffset",
        x = if (isInteractive) offsetX else 0f,
        y = if (isInteractive) offsetY else 0f,
        paramName = "uIntensity",
        paramValue = intensity.coerceIn(0.2f, 2f),
        modifier = interactiveModifier,
    )
}

/**
 * Specimen #14 (`shaders.plaidHyperspaceStarfield`, `periodMs = 16_000`, S45–S46):
 * Full-bleed *Spaceballs* "They've gone to plaid!" hyperspace tartan warp-tunnel & golden cosmic
 * starfield SkSL backdrop shader with tunable [uPlaidMix].
 */
@Composable
fun PlaidHyperspaceStarfieldSpecimen(
    loopMillis: Long,
    uPlaidMix: Float = 0.65f,
    isInteractive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val iTime = loopMillisToRadians(loopMillis, ShadersSpecimenIds.PLAID_HYPERSPACE_STARFIELD_PERIOD_MS)
    var interactiveMixOffset by remember { mutableFloatStateOf(0f) }
    val resolvedMix = if (isInteractive) {
        (uPlaidMix + interactiveMixOffset).coerceIn(0f, 1f)
    } else {
        uPlaidMix.coerceIn(0f, 1f)
    }

    val interactiveModifier = if (isInteractive) {
        modifier
            .stageInteractive(ShadersSpecimenIds.PLAID_HYPERSPACE_STARFIELD, "Plaid Hyperspace Starfield")
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    interactiveMixOffset = (interactiveMixOffset + dragAmount.x / 400f).coerceIn(-1f, 1f)
                }
            }
    } else {
        modifier
    }

    TimeParamSkslBox(
        sksl = ShadersShowcaseSkslSources.PlaidHyperspaceStarfield,
        iTime = iTime,
        paramName = "uPlaidMix",
        paramValue = resolvedMix,
        modifier = interactiveModifier,
    )
}
