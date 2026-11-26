package com.zen.bmwdash;

import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.util.Log;
import android.view.View;

/** Brightens the live camera picture for night driving (Android 13 and newer). */
final class NightView {
    private static final String SHADER =
            "uniform shader content; uniform float green; uniform float boost; uniform float clear;\n"
            + "half4 main(float2 p) {\n"
            + "  half4 c = content.eval(p);\n"
            + "  float3 x = max(float3(c.rgb), float3(0.0));\n"
            + "  if (clear > 0.5) {\n"
            // sharpen against the neighbourhood, add contrast, then tame glare
            + "    float3 n = float3(content.eval(p + float2(4.0, 0.0)).rgb) + float3(content.eval(p - float2(4.0, 0.0)).rgb)\n"
            + "             + float3(content.eval(p + float2(0.0, 4.0)).rgb) + float3(content.eval(p - float2(0.0, 4.0)).rgb);\n"
            + "    x = x + (x - n * 0.25) * 0.9;\n"
            + "    x = clamp((x - 0.45) * 1.30 + 0.43, 0.0, 1.0);\n"
            + "    x = x - max(x - 0.80, float3(0.0)) * 0.45;\n"
            + "  }\n"
            + "  if (boost > 0.5) x = clamp((pow(x, float3(0.55)) - 0.05) * 1.10, 0.0, 1.0);\n"
            + "  float l = dot(x, float3(0.299, 0.587, 0.114));\n"
            + "  float3 g = float3(l * 0.18, min(1.0, l * 1.18), l * 0.34);\n"
            + "  return half4(half3(mix(x, g, green)), c.a);\n"
            + "}\n";

    /** @param mode night view: 0 off, 1 boost, 2 green; @param clear the bad-weather filter */
    static void apply(View v, int mode, boolean clear) {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            if (mode == 0 && !clear) { v.setRenderEffect(null); return; }
            RuntimeShader s = new RuntimeShader(SHADER);
            s.setFloatUniform("green", mode == 2 ? 1f : 0f);
            s.setFloatUniform("boost", mode > 0 ? 1f : 0f);
            s.setFloatUniform("clear", clear ? 1f : 0f);
            v.setRenderEffect(RenderEffect.createRuntimeShaderEffect(s, "content"));
        } catch (RuntimeException e) { Log.w("NightView", e); }
    }
}
