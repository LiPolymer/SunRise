package ink.lipoly.app.sunrise.drop

/** Android actual type is android.content.Context. */
expect abstract class DropHost

expect fun createDropClient(host: DropHost, options: DropOptions = DropOptions()): DropClient
