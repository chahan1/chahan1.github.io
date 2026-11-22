package tw.chahan.engnotebook;

import android.app.Activity;
import android.os.CancellationSignal;

import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import org.json.JSONObject;

/** 原生 Google 登入（Credential Manager）：拿到 ID token 交給網頁，網頁再用它登入 Firebase */
final class GoogleLogin {
    private GoogleLogin() { }

    static void start(Activity a, int id, String clientId) {
        try {
            GetSignInWithGoogleOption opt = new GetSignInWithGoogleOption.Builder(clientId).build();
            GetCredentialRequest req = new GetCredentialRequest.Builder().addCredentialOption(opt).build();
            CredentialManager.create(a).getCredentialAsync(a, req, new CancellationSignal(), a.getMainExecutor(),
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override public void onResult(GetCredentialResponse r) {
                        try {
                            Credential c = r.getCredential();
                            if (c instanceof CustomCredential && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(c.getType())) {
                                GoogleIdTokenCredential g = GoogleIdTokenCredential.createFrom(c.getData());
                                JSONObject o = new JSONObject();
                                o.put("token", g.getIdToken());
                                o.put("email", g.getId());
                                Bridge.emit("g", id, "ok", o.toString());
                            } else fail(id, "unexpected", "拿到的不是 Google 帳號憑證");
                        } catch (Exception e) { fail(id, "parse", String.valueOf(e.getMessage())); }
                    }
                    @Override public void onError(GetCredentialException e) {
                        if (e instanceof GetCredentialCancellationException) fail(id, "cancel", String.valueOf(e.getMessage()));
                        else fail(id, String.valueOf(e.getType()), String.valueOf(e.getMessage()));
                    }
                });
        } catch (Exception e) { fail(id, "start", String.valueOf(e.getMessage())); }
    }

    private static void fail(int id, String code, String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("code", code); o.put("msg", msg);
            Bridge.emit("g", id, "err", o.toString());
        } catch (Exception ignored) { }
    }
}
