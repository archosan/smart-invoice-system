package com.archosan.invoice.document.api;

import com.archosan.invoice.document.admin.DeadLetterConflictException;
import com.archosan.invoice.document.admin.DeadLetterNotFoundException;
import com.archosan.invoice.document.review.InvalidCorrectionException;
import com.archosan.invoice.document.review.MissingReasonException;
import com.archosan.invoice.document.review.ReviewConflictException;
import com.archosan.invoice.document.review.ReviewDocumentNotFoundException;
import com.archosan.invoice.document.review.ReviewForbiddenException;
import com.archosan.invoice.document.review.VersionMismatchException;
import com.archosan.invoice.document.settings.InvalidSettingException;
import com.archosan.invoice.document.storage.NotPdfException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Servise özgü hataların {@link ProblemDetail} (RFC 9457) karşılıkları. Çerçeve hataları (eksik parça veya geçersiz
 * parametre tipi 400, boyut sınırı 413) {@code spring.mvc.problemdetails.enabled} ile aynı biçimde döner.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotPdfException.class)
    ProblemDetail notPdf(NotPdfException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage());
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    ProblemDetail notFound(DocumentNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(DeadLetterNotFoundException.class)
    ProblemDetail deadLetterNotFound(DeadLetterNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(DeadLetterConflictException.class)
    ProblemDetail deadLetterConflict(DeadLetterConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(ReviewDocumentNotFoundException.class)
    ProblemDetail reviewNotFound(ReviewDocumentNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ReviewConflictException.class)
    ProblemDetail reviewConflict(ReviewConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(ReviewForbiddenException.class)
    ProblemDetail reviewForbidden(ReviewForbiddenException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(PreconditionRequiredException.class)
    ProblemDetail preconditionRequired(PreconditionRequiredException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_REQUIRED, e.getMessage());
    }

    @ExceptionHandler(VersionMismatchException.class)
    ProblemDetail versionMismatch(VersionMismatchException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_FAILED, e.getMessage());
    }

    /** İhlal edilen kurallar {@code violations} alanında, {@code ruleResults} biçiminde. */
    @ExceptionHandler(InvalidCorrectionException.class)
    ProblemDetail invalidCorrection(InvalidCorrectionException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        problem.setProperty("violations", e.violations());
        return problem;
    }

    @ExceptionHandler(MissingReasonException.class)
    ProblemDetail missingReason(MissingReasonException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(InvalidSettingException.class)
    ProblemDetail invalidSetting(InvalidSettingException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(InvalidQueryException.class)
    ProblemDetail invalidQuery(InvalidQueryException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
