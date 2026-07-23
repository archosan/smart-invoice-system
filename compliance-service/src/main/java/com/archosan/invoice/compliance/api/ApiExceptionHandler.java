package com.archosan.invoice.compliance.api;

import com.archosan.invoice.compliance.contract.InvalidContractException;
import com.archosan.invoice.compliance.contract.NotPdfException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Servise özgü hataların {@link ProblemDetail} karşılıkları; çerçeve hataları (eksik parametre, 413) aynı biçimde. */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(NotPdfException.class)
    ProblemDetail notPdf(NotPdfException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage());
    }

    @ExceptionHandler(InvalidContractException.class)
    ProblemDetail invalid(InvalidContractException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(ContractNotFoundException.class)
    ProblemDetail notFound(ContractNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
