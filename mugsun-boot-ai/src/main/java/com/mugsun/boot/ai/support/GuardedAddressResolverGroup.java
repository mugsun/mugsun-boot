package com.mugsun.boot.ai.support;

import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.InetNameResolver;
import io.netty.resolver.InetSocketAddressResolver;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * Reactor Netty 连接用的解析器组：每次建连都经 {@link UrlSafetyGate#resolveSafe} 解析并校验，
 * 连接直接使用校验通过的地址——校验的 IP 就是实际连接的 IP，DNS rebinding 无法在两次解析之间换地址。
 */
final class GuardedAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

	private final Supplier<Collection<String>> allowHosts;
	private final UrlSafetyGate.HostResolver resolver;

	GuardedAddressResolverGroup(Supplier<Collection<String>> allowHosts, UrlSafetyGate.HostResolver resolver) {
		this.allowHosts = allowHosts;
		this.resolver = resolver;
	}

	@Override
	protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
		return new InetSocketAddressResolver(executor, new InetNameResolver(executor) {
			@Override
			protected void doResolve(String host, Promise<InetAddress> promise) {
				try {
					promise.setSuccess(UrlSafetyGate.resolveSafe(host, allowHosts.get(), resolver)[0]);
				} catch (Throwable t) {
					promise.setFailure(t);
				}
			}

			@Override
			protected void doResolveAll(String host, Promise<List<InetAddress>> promise) {
				try {
					promise.setSuccess(Arrays.asList(UrlSafetyGate.resolveSafe(host, allowHosts.get(), resolver)));
				} catch (Throwable t) {
					promise.setFailure(t);
				}
			}
		});
	}
}
