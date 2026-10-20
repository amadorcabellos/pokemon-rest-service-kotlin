package com.ballastlane.pokemon.web;

import com.ballastlane.pokemon.auth.AuthService;
import com.ballastlane.pokemon.auth.OpenApiSecurity;
import com.ballastlane.pokemon.web.dto.LoginRequest;
import com.ballastlane.pokemon.web.dto.RefreshTokenRequest;
import com.ballastlane.pokemon.web.dto.RegisterRequest;
import com.ballastlane.pokemon.web.dto.TokenResponse;
import com.ballastlane.pokemon.web.dto.UserProfile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auxiliary API: register, sign in, refresh, sign out, and read the signed-in user.
 */
@Tag(name = "Auth", description = "Register, sign in, refresh and sign out. Access tokens unlock the protected "
		+ "operations (local data updates for USER, synchronization for ADMIN)")
@RestController
@RequestMapping(AuthController.BASE_PATH)
public class AuthController {

	static final String BASE_PATH = "/api/v1/auth";

	private static final String PROBLEM_JSON = "application/problem+json";

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@Operation(summary = "Register a user",
			description = "Creates a user with the USER role. The username is stored in lowercase and must be "
					+ "unique ignoring case.")
	@ApiResponse(responseCode = "201", description = "User registered",
			headers = @Header(name = HttpHeaders.LOCATION, description = "/api/v1/auth/user"))
	@ApiResponse(responseCode = "400", description = "Invalid username or password (listed in 'errors')",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "Username already taken",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@PostMapping("/register")
	public ResponseEntity<UserProfile> register(@RequestBody RegisterRequest request) {
		UserProfile user = UserProfile.from(authService.register(request.username(), request.password()));
		return ResponseEntity.created(URI.create(BASE_PATH + "/user")).body(user);
	}

	@Operation(summary = "Sign in",
			description = "Starts a session and returns an access token (15 minutes by default) and a refresh "
					+ "token (7 days by default). After 5 failed attempts for a username within 15 minutes it "
					+ "is locked for 15 minutes.")
	@ApiResponse(responseCode = "200", description = "Signed in")
	@ApiResponse(responseCode = "400", description = "Missing username or password",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "Wrong username or password (the same answer for both)",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "Too many failed attempts; see Retry-After",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@PostMapping("/login")
	public TokenResponse login(@RequestBody LoginRequest request) {
		return TokenResponse.from(authService.login(request.username(), request.password()));
	}

	@Operation(summary = "Refresh the tokens",
			description = "Ends the refresh token and returns a new access token and refresh token. No access "
					+ "token is needed. The previous access token stops working. Using a refresh token a second "
					+ "time ends the whole session.")
	@ApiResponse(responseCode = "200", description = "New tokens")
	@ApiResponse(responseCode = "400", description = "Missing refresh token",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "Unknown, expired or used refresh token, or ended session",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@PostMapping("/refresh")
	public TokenResponse refresh(@RequestBody RefreshTokenRequest request) {
		return TokenResponse.from(authService.refresh(request.refreshToken()));
	}

	@Operation(summary = "Sign out",
			description = "Ends the session of the refresh token: its access token and refresh token stop working "
					+ "at once. No access token is needed. Other sessions of the user are not affected. Always 204 "
					+ "for a well-formed request, whether or not the token was valid.")
	@ApiResponse(responseCode = "204", description = "Signed out")
	@ApiResponse(responseCode = "400", description = "Missing refresh token",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(@RequestBody RefreshTokenRequest request) {
		authService.logout(request.refreshToken());
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "Get the signed-in user")
	@SecurityRequirement(name = OpenApiSecurity.BEARER)
	@ApiResponse(responseCode = "200", description = "The signed-in user")
	@ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@GetMapping("/user")
	public UserProfile currentUser(@AuthenticationPrincipal Jwt jwt) {
		return Optional.ofNullable(authService.findUser(Long.parseLong(jwt.getSubject())))
				.map(UserProfile::from)
				// Checked moments ago by the security filter; only a deletion in between gets here (401).
				.orElseThrow(() -> new InvalidBearerTokenException("The access token is no longer valid"));
	}

}
