package org.test.remediation;

/**
 * In-memory pair of authoring artifacts produced by the PR authoring layer.
 */
public record PrAuthorArtifacts(PrAuthorRequest request, PrChangePlan changePlan) {
}

