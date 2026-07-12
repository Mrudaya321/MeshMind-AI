package com.qualcomm.meshmind.exceptions

/**
 * Root sealed exception for all custom failures occurring within the MeshMind Edge application.
 */
sealed class MeshMindException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class PacketParsingException(message: String, cause: Throwable? = null) : 
        MeshMindException(message, cause)

    class SecurityValidationException(message: String, cause: Throwable? = null) : 
        MeshMindException(message, cause)

    class LiteRTInitializationException(message: String, cause: Throwable? = null) : 
        MeshMindException(message, cause)

    class ConfigException(message: String, cause: Throwable? = null) : 
        MeshMindException(message, cause)

    class DatabaseException(message: String, cause: Throwable? = null) : 
        MeshMindException(message, cause)

    class RepositoryException(message: String, cause: Throwable? = null) : 
        MeshMindException(message, cause)

    class TelemetryException(message: String, cause: Throwable? = null) : 
        MeshMindException(message, cause)

    class RoutingException(message: String, cause: Throwable? = null) : 
        MeshMindException(message, cause)

    class DigitalTwinException(message: String, cause: Throwable? = null) : 
        MeshMindException(message, cause)
}
