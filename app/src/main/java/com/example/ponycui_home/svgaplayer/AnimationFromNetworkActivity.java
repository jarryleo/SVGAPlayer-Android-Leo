package com.example.ponycui_home.svgaplayer;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;

import androidx.annotation.Nullable;

import com.opensource.svgaplayer.SVGAImageView;

public class AnimationFromNetworkActivity extends Activity {

    SVGAImageView animationView = null;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        animationView = new SVGAImageView(this);
        animationView.setBackgroundColor(Color.GRAY);
        setContentView(animationView);
        loadAnimation();
    }

    private void loadAnimation() {
        String url = "https://github.com/yyued/SVGA-Samples/blob/master/posche.svga?raw=true";
        animationView.load(url);
    }

}
