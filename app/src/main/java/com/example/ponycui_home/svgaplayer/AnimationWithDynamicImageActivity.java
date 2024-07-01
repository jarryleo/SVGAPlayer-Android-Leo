package com.example.ponycui_home.svgaplayer;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.support.annotation.Nullable;

import com.opensource.svgaplayer.SVGAImageView;

public class AnimationWithDynamicImageActivity extends Activity {

    SVGAImageView animationView = null;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        animationView = new SVGAImageView(this);
        animationView.setBackgroundColor(Color.GRAY);
        loadAnimation();
        setContentView(animationView);
    }

    private void loadAnimation() {
        animationView.load("https://github.com/yyued/SVGA-Samples/blob/master/kingset.svga?raw=true",
                null,
                null,
                svgaDynamicEntity -> {
                    svgaDynamicEntity.setDynamicImage(
                            "https://github.com/PonyCui/resources/blob/master/svga_replace_avatar.png?raw=true",
                            "99"); // Here is the KEY implementation.
                    return null;
                });
    }

}
