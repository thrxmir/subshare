package org.subshare.rest.client.transport;

class RestRepoTransportFactory extends co.codewizards.cloudstore.rest.client.transport.RestRepoTransportFactory {

	@Override
	protected co.codewizards.cloudstore.core.repo.transport.RepoTransport _createRepoTransport() {
		return new RestRepoTransport();
	}

}
