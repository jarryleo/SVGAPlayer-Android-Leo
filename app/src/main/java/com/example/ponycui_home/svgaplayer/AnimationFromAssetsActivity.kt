package com.example.ponycui_home.svgaplayer;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Log;
import android.view.View;

import com.opensource.svgaplayer.SVGADynamicEntity;
import com.opensource.svgaplayer.SVGAImageView;
import com.opensource.svgaplayer.SVGAImageViewExtKt;
import com.opensource.svgaplayer.SVGASoundManager;
import com.opensource.svgaplayer.utils.log.SVGALogger;

import java.util.ArrayList;

public class AnimationFromAssetsActivity extends Activity {

    int currentIndex = 0;
    SVGAImageView animationView = null;
    private ArrayList<String> samples = new ArrayList();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        animationView = new SVGAImageView(this);
        animationView.setBackgroundColor(Color.BLACK);
        animationView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                animationView.stepToFrame(currentIndex++, false);
            }
        });
        SVGALogger.INSTANCE.setLogEnabled(true);
        SVGASoundManager.INSTANCE.init();
        loadAnimation();
        setContentView(animationView);
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void loadAnimation() {
//        String name = this.randomSample();
        //asset jojo_audio.svga  cannot callback
        String name = "cp1.svga";
        SVGAImageViewExtKt.loadAssets(animationView,
                name,
                true,
                true,
                1,
                svgaVideoEntity -> {
                    String id = "ID:123456";
                    String nick  = "nick:test";
                    SVGADynamicEntity svgaDynamicEntity = new SVGADynamicEntity();
                    TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                    textPaint.setColor(Color.WHITE);
                    textPaint.setTextSize(10f);
                    float width = textPaint.measureText(nick, 0, nick.length());
                    StaticLayout.Builder builder =
                            StaticLayout.Builder.obtain(nick,
                                            0,
                                            nick.length(),
                                            textPaint,
                                            (int)width);
                    StaticLayout build = builder.build();
                    svgaDynamicEntity.setDynamicText(build,"nick1");
                    return svgaDynamicEntity;
                }
        );
        Log.d("SVGA", "## name " + name);
    }

    private String randomSample() {
        if (samples.size() == 0) {
            samples.add("750x80.svga");
            samples.add("alarm.svga");
            samples.add("angel.svga");
            samples.add("Castle.svga");
            samples.add("EmptyState.svga");
            samples.add("Goddess.svga");
            samples.add("gradientBorder.svga");
            samples.add("heartbeat.svga");
            samples.add("matteBitmap.svga");
            samples.add("matteBitmap_1.x.svga");
            samples.add("matteRect.svga");
            samples.add("MerryChristmas.svga");
            samples.add("posche.svga");
            samples.add("Rocket.svga");
            samples.add("rose.svga");
            samples.add("rose_2.0.0.svga");
        }
        return samples.get((int) Math.floor(Math.random() * samples.size()));
    }

}
