package io.pacod.cms.studio.i18n;

/**
 * An exception whose text is a message key plus arguments, resolved in the operator's language by
 * {@link MgmtText#of(Throwable)} where it is shown. Its {@code getMessage()} stays English (the
 * base bundle), for logs and for the service tests that assert on it.
 */
public interface MgmtCodedMessage {

    String code();

    Object[] args();
}
