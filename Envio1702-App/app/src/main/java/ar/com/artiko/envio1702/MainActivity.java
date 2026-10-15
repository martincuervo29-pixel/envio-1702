package ar.com.artiko.envio1702;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import java.io.File;

/**
 * Envío 1702 — app del cadete.
 * Abre la app web (Apps Script) dentro de la app y le suma:
 *  - lector de QR nativo (window.Android1702.escanear / resultado),
 *  - cámara para "sacar foto del QR",
 *  - ubicación para el mapa,
 *  - WhatsApp, Google Maps y llamadas en sus apps.
 * Los cambios de la app web se ven solos: no hace falta reinstalar.
 */
public class MainActivity extends Activity {

    static final String URL_APP = "https://script.google.com/macros/s/AKfycbwxp8prBqBwGMTr13AHtiaGm_o-Vk13tCRW23p6h5xQauLet7MoZkc6GOsTLCEzIjm2/exec?r=app";
    static final int REQ_FOTO = 11, REQ_PERMISOS = 12;

    WebView web;
    ValueCallback<Uri[]> archivoCallback;
    Uri fotoUri;
    volatile String ultimoQR = null;   // "" = cancelado, null = esperando

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);           // la app recuerda al cadete
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);      // los links "_blank" pasan por shouldOverrideUrlLoading
        s.setUserAgentString(s.getUserAgentString() + " Envio1702App/1.0");

        web.addJavascriptInterface(new Puente(), "Android1702");
        web.setWebViewClient(new Cliente());
        web.setWebChromeClient(new Chrome());

        pedirPermisos();
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(URL_APP);
    }

    void pedirPermisos() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA}, REQ_PERMISOS);
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }

    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }

    /** Lo que se abre adentro de la app (Google) y lo que se manda a otras apps (WhatsApp, Maps, teléfono). */
    static boolean esInterno(Uri u) {
        String h = u.getHost() == null ? "" : u.getHost();
        return "https".equals(u.getScheme()) && (h.equals("script.google.com") || h.endsWith(".googleusercontent.com")
                || h.equals("accounts.google.com") || h.endsWith(".gstatic.com"));
    }

    void abrirAfuera(Uri u) {
        try {
            Intent i = "tel".equals(u.getScheme()) ? new Intent(Intent.ACTION_DIAL, u) : new Intent(Intent.ACTION_VIEW, u);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No hay una app para abrir este link.", Toast.LENGTH_SHORT).show();
        }
    }

    class Cliente extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if (esInterno(u)) return false;
            abrirAfuera(u);
            return true;
        }
    }

    class Chrome extends WebChromeClient {
        // Cámara en vivo pedida por la página (si Google la deja pasar).
        @Override public void onPermissionRequest(PermissionRequest request) { runOnUiThread(() -> request.grant(request.getResources())); }

        @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) { cb.invoke(origin, true, false); }

        // "Sacar foto del QR": abre la cámara del teléfono.
        @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
            if (archivoCallback != null) archivoCallback.onReceiveValue(null);
            archivoCallback = cb;
            try {
                File dir = new File(getCacheDir(), "fotos");
                dir.mkdirs();
                File f = File.createTempFile("qr", ".jpg", dir);
                fotoUri = FileProvider.getUriForFile(MainActivity.this, "ar.com.artiko.envio1702.fotos", f);
                Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                cam.putExtra(MediaStore.EXTRA_OUTPUT, fotoUri);
                cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                Intent elegir = Intent.createChooser(p.createIntent(), "Foto del QR");
                elegir.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
                startActivityForResult(elegir, REQ_FOTO);
                return true;
            } catch (Exception e) {
                archivoCallback = null;
                return false;
            }
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FOTO || archivoCallback == null) return;
        Uri[] r = null;
        if (res == RESULT_OK) r = (data != null && data.getData() != null) ? new Uri[]{data.getData()} : new Uri[]{fotoUri};
        archivoCallback.onReceiveValue(r);
        archivoCallback = null;
    }

    /** Puente para la página: escanear() abre el lector nativo; resultado() devuelve el texto leído. */
    class Puente {
        @JavascriptInterface public boolean disponible() { return true; }

        @JavascriptInterface public void escanear() {
            ultimoQR = null;
            runOnUiThread(() -> {
                GmsBarcodeScannerOptions o = new GmsBarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build();
                GmsBarcodeScanner sc = GmsBarcodeScanning.getClient(MainActivity.this, o);
                sc.startScan()
                        .addOnSuccessListener(b -> ultimoQR = b.getRawValue() == null ? "" : b.getRawValue())
                        .addOnCanceledListener(() -> ultimoQR = "")
                        .addOnFailureListener(e -> ultimoQR = "");
            });
        }

        /** null mientras escanea, "" si se canceló, o el texto del QR. */
        @JavascriptInterface public String resultado() { String r = ultimoQR; if (r != null) ultimoQR = null; return r; }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) { super.onRequestPermissionsResult(req, perms, res); }
}
