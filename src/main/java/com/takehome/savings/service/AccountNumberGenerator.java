package com.takehome.savings.service;

/**
 * Produces candidate public account numbers for new savings accounts.
 *
 * <p>Implementations only guarantee <em>format validity</em>, not uniqueness; the caller
 * (the service layer) owns collision handling and the DB unique constraint is the final
 * backstop. Keeping this an interface allows swapping in a sequenced strategy later - see
 * docs/DESIGN-TRADEOFFS.md.
 */
public interface AccountNumberGenerator {

    /**
     * @return a new, format-valid account number (unique only with the caller's check).
     */
    String generate();
}
