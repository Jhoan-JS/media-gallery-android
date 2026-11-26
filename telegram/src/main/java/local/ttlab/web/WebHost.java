package local.ttlab.web;

import local.ttlab.telegram.BuildConfig;

final class WebHost {
    static final String GALLERY_PACKAGE = BuildConfig.GALLERY_PACKAGE;
    static final String PERMISSION = "local.ttlab.permission.TELEGRAM_WEB";
    static final boolean FIXTURE = BuildConfig.WEB_FIXTURE;
    static final boolean ALLOW_LIVE_FIXTURE = false;
}
