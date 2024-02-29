package com.example.ponycui_home.svgaplayer;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.View;

import com.opensource.svgaplayer.SVGAImageView;
import com.opensource.svgaplayer.SVGAImageViewExtKt;
import com.opensource.svgaplayer.SVGAParser;
import com.opensource.svgaplayer.SVGASoundManager;
import com.opensource.svgaplayer.utils.log.SVGALogger;

import java.util.ArrayList;

public class AnimationFromAssetsActivity extends Activity {

    int currentIndex = 0;
    SVGAImageView animationView = null;

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

    private void loadAnimation() {
//        String name = this.randomSample();
        //asset jojo_audio.svga  cannot callback
        String name = "mp3_to_long.svga";
        SVGAImageViewExtKt.loadAssets(animationView, name);
        Log.d("SVGA", "## name " + name);
    }

    private ArrayList<String> samples = new ArrayList();

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
