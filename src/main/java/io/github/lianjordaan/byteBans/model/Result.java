package io.github.lianjordaan.byteBans.model;

public class Result {
    private final Boolean success;
    private final String message;

    public Result(Boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public Boolean isSuccess() { return success; }
    public String getMessage() { return message; }
}
