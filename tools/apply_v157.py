#!/usr/bin/env python3
"""一次性迁移：v1.5.7 在主界面加「立即签到 / 检测升级 / 申请 root 授权」入口（幂等）。"""
import pathlib
import sys

MAIN = pathlib.Path("app/src/main/java/com/doubao/autocheckin/MainActivity.java")

OLD = '        findViewById(R.id.btnCheckinNow).setOnClickListener(v -> runNow("手动打卡"));\n'

NEW = '''        android.widget.Button btnNow = findViewById(R.id.btnCheckinNow);
        btnNow.setText("立即签到");
        btnNow.setOnClickListener(v -> runNow("立即签到"));
        // v1.5.7：检测升级 + 申请 root 授权，横排在今日打卡卡片底部
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.Button btnUpdate = new android.widget.Button(this);
        btnUpdate.setText("检测升级");
        btnUpdate.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.Button btnRoot = new android.widget.Button(this);
        btnRoot.setText("申请 root 授权");
        btnRoot.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnRow.addView(btnUpdate);
        btnRow.addView(btnRoot);
        LinearLayout btnCard = (LinearLayout) btnNow.getParent();
        btnCard.addView(btnRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        btnUpdate.setOnClickListener(v -> UpdateChecker.check(this));
        btnRoot.setOnClickListener(v -> RootHelper.request(this));
'''


def main():
    s = MAIN.read_text(encoding="utf-8")
    if "btnNow.setText" in s:
        print("already patched, nothing to do")
        return
    if OLD not in s:
        print("ANCHOR MISSING: btnCheckinNow")
        sys.exit(1)
    s = s.replace(OLD, NEW, 1)
    MAIN.write_text(s, encoding="utf-8")
    print("apply: MainActivity buttons")


if __name__ == "__main__":
    main()
