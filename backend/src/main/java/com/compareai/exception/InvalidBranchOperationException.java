package com.compareai.exception;

/**
 * Bir mesaj/dal işlemi, ait olmadığı bir konuşma üzerinde yapılmaya
 * çalışıldığında (örn. başka bir conversation'a ait parentMessageId
 * gönderilmesi) fırlatılır.
 */
public class InvalidBranchOperationException extends RuntimeException {

    public InvalidBranchOperationException(String message) {
        super(message);
    }
}