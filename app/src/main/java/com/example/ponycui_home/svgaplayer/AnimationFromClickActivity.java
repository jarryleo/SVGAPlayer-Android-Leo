package com.example.ponycui_home.svgaplayer;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.opensource.svgaplayer.SVGAClickAreaListener;
import com.opensource.svgaplayer.SVGAImageView;

import org.jetbrains.annotations.NotNull;


/**
 * Created by miaojun on 2019/6/21.
 * mail:1290846731@qq.com
 */
public class AnimationFromClickActivity extends Activity {

    SVGAImageView animationView = null;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        animationView = new SVGAImageView(this);
        animationView.setOnAnimKeyClickListener(new SVGAClickAreaListener() {
            @Override
            public void onClick(@NotNull String clickKey) {
                Toast.makeText(AnimationFromClickActivity.this, clickKey, Toast.LENGTH_SHORT).show();
            }
        });
        animationView.setBackgroundColor(Color.WHITE);
        loadAnimation();
        setContentView(animationView);
    }

    private void loadAnimation() {
        animationView.load("MerryChristmas.svga", null, null,
                svgaDynamicEntity -> {
                    svgaDynamicEntity.setClickArea("img_10");
                    return null;
                }
        );
    }

}

