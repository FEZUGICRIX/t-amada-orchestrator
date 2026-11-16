package com.tamada.travel.orchestrator.service.error;

import com.tamada.travel.orchestrator.exception.ExternalBookingErrorType;
import com.tamada.travel.orchestrator.model.BookingType;
import org.springframework.stereotype.Component;

/**
 * Сервис формирования понятных и информативных сообщений об ошибках и причинах отмены для пользователя.
 */
@Component
public class UserErrorMessageResolver {

    /**
     * Формирует понятное сообщение об ошибке бронирования конкретной услуги.
     */
    public String resolveBookingErrorMessage(
            BookingType type,
            ExternalBookingErrorType errorType,
            Integer httpStatus,
            String supplierMessage
    ) {
        String serviceName = formatServiceName(type);

        if (errorType == null) {
            return "Не удалось забронировать " + serviceName + ": " + cleanMessage(supplierMessage, "неизвестная ошибка");
        }

        return switch (errorType) {
            case RESOURCE_UNAVAILABLE -> switch (type) {
                case HOTEL -> "Выбранный номер в отеле недоступен для бронирования на указанные даты.";
                case FLIGHT -> "Выбранный авиарейс недоступен: закончились места или закрыта продажа билетов.";
                case INSURANCE -> "Оформление выбранного страхового полиса временно недоступно.";
                case TRANSFER -> "Выбранный трансфер или автомобиль недоступен для бронирования.";
            };
            case NOT_FOUND -> "Выбранная услуга (" + serviceName + ") не найдена в каталоге поставщика.";
            case VALIDATION_ERROR -> {
                String detail = cleanMessage(supplierMessage, null);
                if (detail != null && !detail.isBlank()) {
                    yield "Некорректные параметры бронирования (" + serviceName + "): " + detail + ".";
                }
                yield "Некорректные параметры запроса при бронировании (" + serviceName + ").";
            }
            case SYSTEM_FAILURE -> "Сервис поставщика (" + serviceName + ") временно недоступен или не отвечает. Пожалуйста, повторите попытку позже.";
            case UNKNOWN -> {
                if (httpStatus != null && httpStatus >= 500) {
                    yield "Сервис поставщика (" + serviceName + ") вернул ошибку сервера. Попробуйте позже.";
                }
                String detail = cleanMessage(supplierMessage, null);
                if (detail != null && !detail.isBlank()) {
                    yield "Ошибка при бронировании (" + serviceName + "): " + detail + ".";
                }
                yield "Не удалось забронировать " + serviceName + ".";
            }
        };
    }

    /**
     * Формирует причину отмены подтвержденной услуги в рамках компенсации (Saga compensation).
     */
    public String resolveCompensationReason(
            BookingType compensatedType,
            BookingType failedType,
            String failureUserMessage
    ) {
        String compName = formatServiceName(compensatedType);
        String failedName = formatServiceName(failedType);
        return "Отменено: компенсация бронирования " + compName + " в связи со сбоем бронирования " + failedName
                + (failureUserMessage != null && !failureUserMessage.isBlank() ? " (" + failureUserMessage + ")" : "");
    }

    /**
     * Формирует понятное пользователю сообщение об отмене услуги в рамках компенсации.
     */
    public String resolveCompensationUserMessage(
            BookingType compensatedType,
            BookingType failedType
    ) {
        String compName = formatServiceName(compensatedType);
        String failedName = formatServiceName(failedType);
        return "Бронирование (" + compName + ") автоматически отменено в связи с недоступностью другой услуги заказа (" + failedName + ").";
    }

    /**
     * Формирует причину отмены (пропуска) еще не начатой услуги.
     */
    public String resolveSkippedStepReason(
            BookingType skippedType,
            BookingType failedType,
            String failureUserMessage
    ) {
        String skippedName = formatServiceName(skippedType);
        String failedName = formatServiceName(failedType);
        return "Отменено (не выполнялось): бронирование " + skippedName + " отменено в связи со сбоем шага " + failedName
                + (failureUserMessage != null && !failureUserMessage.isBlank() ? " (" + failureUserMessage + ")" : "");
    }

    /**
     * Формирует понятное пользователю сообщение для пропущенного шага.
     */
    public String resolveSkippedStepUserMessage(
            BookingType skippedType,
            BookingType failedType
    ) {
        String skippedName = formatServiceName(skippedType);
        String failedName = formatServiceName(failedType);
        return "Бронирование (" + skippedName + ") отменено и не выполнялось из-за сбоя в услуге (" + failedName + ").";
    }

    /**
     * Формирует причину ручной отмены услуги.
     */
    public String resolveManualCancellationReason(BookingType type) {
        return "Отменено по запросу пользователя";
    }

    /**
     * Формирует понятное пользователю сообщение при ручной отмене.
     */
    public String resolveManualCancellationUserMessage(BookingType type) {
        return "Услуга (" + formatServiceName(type) + ") отменена по запросу пользователя.";
    }

    /**
     * Формирует итоговое сообщение по заказу в целом при сбое одного из шагов.
     */
    public String resolveOrderFailureMessage(BookingType failedType, String failureUserMessage) {
        String failedName = formatServiceName(failedType);
        return "Заказ не может быть выполнен: сбой при бронировании " + failedName
                + (failureUserMessage != null && !failureUserMessage.isBlank() ? " (" + failureUserMessage + ")" : "")
                + ". Ранее забронированные услуги успешно отменены.";
    }

    public String formatServiceName(BookingType type) {
        if (type == null) {
            return "услуги";
        }
        return switch (type) {
            case FLIGHT -> "авиабилета";
            case HOTEL -> "отеля";
            case INSURANCE -> "страховки";
            case TRANSFER -> "трансфера";
        };
    }

    private String cleanMessage(String message, String fallback) {
        if (message == null || message.isBlank()) {
            return fallback;
        }
        return message.trim();
    }
}
