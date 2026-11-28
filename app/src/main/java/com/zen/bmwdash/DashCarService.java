package com.zen.bmwdash;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;

/** Shows BMW Dash in the Android Auto app list; opening it shows the road view on the car screen. */
public final class DashCarService extends CarAppService {
    @NonNull @Override public HostValidator createHostValidator() {
        // a personal sideloaded app: any Android Auto host may connect
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
    }

    @NonNull @Override public Session onCreateSession() {
        return new Session() {
            @NonNull @Override public Screen onCreateScreen(@NonNull Intent intent) {
                return new DashCarScreen(getCarContext());
            }
        };
    }
}
