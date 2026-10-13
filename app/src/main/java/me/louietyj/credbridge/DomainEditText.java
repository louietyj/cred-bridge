package me.louietyj.credbridge;

import android.content.Context;
import android.view.ViewStructure;
import android.widget.EditText;

/**
 * An EditText that tells the autofill service it belongs to a page on {@code webDomain}, so the
 * service offers that site's saved logins. This is the browser contract; the service decides
 * whether to trust it (Google's warns once per app before filling).
 */
final class DomainEditText extends EditText {

    private final String webDomain;

    DomainEditText(Context context, String webDomain) {
        super(context);
        this.webDomain = webDomain;
    }

    @Override
    public void onProvideAutofillStructure(ViewStructure structure, int flags) {
        super.onProvideAutofillStructure(structure, flags);
        structure.setWebDomain(webDomain);
    }
}
