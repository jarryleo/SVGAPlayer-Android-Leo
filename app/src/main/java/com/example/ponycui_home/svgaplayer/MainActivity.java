package com.example.ponycui_home.svgaplayer;

import android.content.Intent;
import android.database.DataSetObserver;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.opensource.svgaplayer.SVGAManager;

import java.util.ArrayList;

class SampleItem {

    String title;
    Intent intent;

    public SampleItem(String title, Intent intent) {
        this.title = title;
        this.intent = intent;
    }

}

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    ListView listView;
    Button button;
    ArrayList<SampleItem> items = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        listView = findViewById(R.id.listView);
        button = findViewById(R.id.btn_clear_cache);
        button.setOnClickListener(this);
        this.setupData();
        this.setupListView();
    }

    void setupData() {
        this.items.add(new SampleItem("Animation From Assets", new Intent(this, AnimationFromAssetsActivity.class)));
        this.items.add(new SampleItem("Animation From Network", new Intent(this, AnimationFromNetworkActivity.class)));
        this.items.add(new SampleItem("Animation From Layout XML", new Intent(this, AnimationFromLayoutActivity.class)));
        this.items.add(new SampleItem("Animation With Dynamic Image", new Intent(this, AnimationWithDynamicImageActivity.class)));
        this.items.add(new SampleItem("Animation With Dynamic Click", new Intent(this, AnimationFromClickActivity.class)));
        this.items.add(new SampleItem("SVGAList(本地资源)", new Intent(this, SVGAListAssetsActivity.class)));
        this.items.add(new SampleItem("SVGAList(网络资源)", new Intent(this, SVGARvListNetworkActivity.class)));
        this.items.add(new SampleItem("内存缓存(本地资源)", new Intent(this, SVGACacheAssetsActivity.class)));
        this.items.add(new SampleItem("内存缓存(网络资源)", new Intent(this, SVGACacheNetworkActivity.class)));
    }

    void setupListView() {
        this.listView.setAdapter(new ListAdapter() {
            @Override
            public boolean areAllItemsEnabled() {
                return false;
            }

            @Override
            public boolean isEnabled(int i) {
                return false;
            }

            @Override
            public void registerDataSetObserver(DataSetObserver dataSetObserver) {

            }

            @Override
            public void unregisterDataSetObserver(DataSetObserver dataSetObserver) {

            }

            @Override
            public int getCount() {
                return MainActivity.this.items.size();
            }

            @Override
            public Object getItem(int i) {
                return null;
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public boolean hasStableIds() {
                return false;
            }

            @Override
            public View getView(final int i, View view, ViewGroup viewGroup) {
                LinearLayout linearLayout = new LinearLayout(MainActivity.this);
                TextView textView = new TextView(MainActivity.this);
                textView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        MainActivity.this.startActivity(MainActivity.this.items.get(i).intent);
                    }
                });
                textView.setText(MainActivity.this.items.get(i).title);
                textView.setTextSize(24);
                textView.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
                linearLayout.addView(textView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (55 * getResources().getDisplayMetrics().density)));
                return linearLayout;
            }

            @Override
            public int getItemViewType(int i) {
                return 1;
            }

            @Override
            public int getViewTypeCount() {
                return 1;
            }

            @Override
            public boolean isEmpty() {
                return false;
            }
        });
        this.listView.setBackgroundColor(Color.WHITE);
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.btn_clear_cache) {
            SVGAManager.clearCache();
        }
    }
}
