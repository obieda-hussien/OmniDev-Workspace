package com.omnidev.system.contract;
import android.os.Bundle;

/** Fixed actions only. No shell, credential or unrestricted intent executor. */
interface IOmniSystemService {
    Bundle execute(String action, in Bundle arguments);
}
