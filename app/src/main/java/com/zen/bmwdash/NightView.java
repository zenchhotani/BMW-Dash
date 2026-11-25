package com.zen.bmwdash;

import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.util.Log;
import android.view.View;

/** Brightens the live camera picture for night driving (Android 13 and newer). */
final class NightView {
    private static final String SHADER =
            "uniform shader content; uniform float green;\n"
            + "half4 main(float2 p) {\n"
            + "  half4 c = content.eval(p);\n"
            + "  float3 x = pow(max(float3(c.rgb), float3(0.0)), float3(0.55));\n"
            + "  x = clamp((x - 0.05) * 1.10, 0.0, 1.0);\n"
            + "  float l = dot(x, float3(0.299, 0.587, 0.114));\n"
            + "  float3 g = float3(l * 0.18, min(1.0, l * 1.18), l * 0.34);\n"
            + "  return half4(half3(mix(x, g, green)), c.a);\n"
            + "}\n";

    /** @param mode 0 off, 1 boost, 2 green */
    static void apply(View v, int mode) {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            if (mode == 0) { v.setRenderEffect(null); return; }
            RuntimeShader s = new RuntimeShader(SHADER);
            s.setFloatUniform("green", mode == 2 ? 1f : 0f);
            v.setRenderEffect(RenderEffect.createRuntimeShaderEffect(s, "content"));
        } catch (RuntimeException e) { Log.w("NightView", e); }
    }
}
