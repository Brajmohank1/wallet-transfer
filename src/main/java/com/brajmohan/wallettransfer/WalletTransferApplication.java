package com.brajmohan.wallettransfer;

import java.net.URI;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WalletTransferApplication {

	public static void main(String[] args) {
		applyDatabaseUrlIfPresent();
		SpringApplication.run(WalletTransferApplication.class, args);
	}

	// Free-tier Postgres add-ons inject a single DATABASE_URL in
	// "postgres://user:pass@host:port/db" form, not the "jdbc:postgresql://"
	// form Spring's DataSource expects, so translate it before the context
	// starts rather than requiring three separately-named env vars.
	private static void applyDatabaseUrlIfPresent() {
		String databaseUrl = System.getenv("DATABASE_URL");
		if (databaseUrl == null || databaseUrl.isBlank() || databaseUrl.startsWith("jdbc:")) {
			return;
		}
		URI uri = URI.create(databaseUrl);
		int port = uri.getPort() == -1 ? 5432 : uri.getPort();
		// Keep the query string (e.g. sslmode) -- some platforms' internal
		// Postgres proxies reject a connection with the driver's own default.
		String query = uri.getQuery();
		String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath()
				+ (query != null && !query.isBlank() ? "?" + query : "");
		System.setProperty("spring.datasource.url", jdbcUrl);

		String userInfo = uri.getUserInfo();
		if (userInfo != null) {
			String[] parts = userInfo.split(":", 2);
			System.setProperty("spring.datasource.username", parts[0]);
			if (parts.length > 1) {
				System.setProperty("spring.datasource.password", parts[1]);
			}
		}
	}

}
