package com.example.ponycui_home.svgaplayer;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.util.Log;

import com.opensource.svgaplayer.SVGAConfig;
import com.opensource.svgaplayer.SVGAImageView;
import com.opensource.svgaplayer.SVGAParser;
import com.opensource.svgaplayer.SVGAVideoEntity;

import org.jetbrains.annotations.NotNull;

import java.net.MalformedURLException;
import java.net.URL;

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
        try { // new URL needs try catch.
            SVGAParser svgaParser = SVGAParser.shareParser();
            svgaParser.decodeFromURL(
                    new URL("https://github.com/yyued/SVGA-Samples/blob/master/posche.svga?raw=true"),
                    new SVGAConfig(
                            animationView.getWidth(),
                            animationView.getHeight(),
                            true
                    ),
                    new SVGAParser.ParseCompletion() {
                        @Override
                        public void onComplete(@NotNull SVGAVideoEntity videoItem) {
                            Log.d("##", "## FromNetworkActivity load onComplete");
                            animationView.setVideoItem(videoItem);
                            animationView.startAnimation();
                        }

                        @Override
                        public void onError() {

                        }


                    },
                    null);
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
    }

}
