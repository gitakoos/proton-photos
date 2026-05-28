package eu.akoos.photos.domain.entity

class StorageFullException(message: String = "Storage quota exceeded") : Exception(message)

class RateLimitedException(val retryAfterSeconds: Int) : Exception("Rate limited, retry after $retryAfterSeconds s")

class DriveNotFoundException(message: String = "Resource not found") : Exception(message)
