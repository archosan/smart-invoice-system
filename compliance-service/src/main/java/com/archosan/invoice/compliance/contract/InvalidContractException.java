package com.archosan.invoice.compliance.contract;

/** Yükleme parametreleri geçersiz; 400 döner. */
public class InvalidContractException extends RuntimeException {

    public InvalidContractException(String message) {
        super(message);
    }
}
