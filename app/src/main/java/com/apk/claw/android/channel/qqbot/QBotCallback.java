package com.apk.claw.android.channel.qqbot;

public interface QBotCallback<T> {
    void onSuccess(T result);
    void onFailure(QBotException e);
}
