package com.stormdroid.fragments;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.stormdroid.GogAuth;
import com.stormdroid.R;

/**
 * GOG sign-in: a WebView on GOG's own login page (from RimDroid). The password stays on GOG's page,
 * along with the CAPTCHA, e-mail two-step and TOTP prompts a headless login could not clear; we only
 * catch the navigation to Galaxy's redirect target and exchange its code for the tokens
 * ({@link GogAuth}). Then back to the Download screen, where the GOG download starts.
 */
public class GogLoginFragment extends Fragment {
    private static final String TAG = "StormDroid/GOG";

    private WebView web;
    private ProgressBar progress;
    private TextView status;
    private boolean codeTaken;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_gog_login, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        web = v.findViewById(R.id.web_gog);
        progress = v.findViewById(R.id.progress_gog);
        status = v.findViewById(R.id.tv_gog_status);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handle(request.getUrl().toString());
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                // The redirect can arrive as a plain navigation rather than one we get to override.
                handle(url);
            }
        });
        web.loadUrl(GogAuth.authUrl());
    }

    /** @return true when the URL was the login callback and we consumed it. */
    private boolean handle(String url) {
        String code = GogAuth.codeFrom(url);
        if (code == null || codeTaken) return false;
        codeTaken = true;
        web.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.gog_login_finishing);
        new Thread(() -> {
            String error = null;
            try {
                CookieManager.getInstance().flush();
                GogAuth.signInWithCode(code);
            } catch (Exception e) {
                Log.e(TAG, "sign-in failed", e);
                error = e.getMessage();
            }
            final String err = error;
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                progress.setVisibility(View.GONE);
                if (err != null) {
                    status.setText(getString(R.string.gog_login_failed, err));
                    return;
                }
                Toast.makeText(requireContext(), R.string.gog_login_ok, Toast.LENGTH_SHORT).show();
                Navigation.findNavController(web).popBackStack();
            });
        }, "sd-gog-token").start();
        return true;
    }
}
