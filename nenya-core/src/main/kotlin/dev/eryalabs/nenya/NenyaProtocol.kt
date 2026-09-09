package dev.eryalabs.nenya

/**
 * Identity of the marketplace microstandard this library implements.
 *
 * Nenya is a protocol layer, not an application: every value here is part of the wire
 * contract that integrating clients and their counterparties agree on. Changing one is
 * a protocol change, not a refactor.
 */
public object NenyaProtocol {

    /**
     * Version of the Nenya marketplace microstandard, carried on every event this
     * library publishes so a peer can tell which rules the sender was following.
     *
     * Bumped only alongside a specification change in `spec/`.
     */
    public const val VERSION: Int = 1

    /** Short identifier used as the specification's name and in event tags. */
    public const val NAME: String = "nenya"
}
