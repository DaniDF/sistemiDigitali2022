package it.dani.cameraapp.motion

/**
 * @author Daniele
 */
class IllegalDetectionException : RuntimeException {
    constructor() : super()
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
    constructor(cause: Throwable) : super(cause)
}