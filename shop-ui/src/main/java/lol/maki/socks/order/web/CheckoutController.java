package lol.maki.socks.order.web;

import am.ik.yavi.core.ConstraintViolations;
import lol.maki.socks.cart.Cart;
import lol.maki.socks.config.SockProps;
import lol.maki.socks.order.Order;
import lol.maki.socks.order.OrderService;
import lol.maki.socks.order.client.OrderResponse;
import lol.maki.socks.security.ShopUser;
import lol.maki.socks.user.client.CustomerAddressResponse;
import lol.maki.socks.user.client.CustomerCardResponse;
import lol.maki.socks.user.client.CustomerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServerOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.CollectionUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.Builder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import org.springframework.web.util.UriComponentsBuilder;

import static lol.maki.socks.cart.web.CartHandlerMethodArgumentResolver.CART_ID_COOKIE_NAME;
import static lol.maki.socks.security.RedirectToServerRedirectStrategy.REDIRECT_TO_ATTR;

@Controller
public class CheckoutController {
	private final OrderService orderService;

	private final WebClient webClient;

	private final SockProps props;

	private final Logger log = LoggerFactory.getLogger(CheckoutController.class);

	public CheckoutController(OrderService orderService, Builder builder, ReactiveOAuth2AuthorizedClientManager authorizedClientManager, SockProps props) {
		this.orderService = orderService;
		this.webClient = builder
				.filter(new ServerOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager))
				.build();
		this.props = props;
	}

	@ModelAttribute("order")
	public Mono<Order> setupForm(@AuthenticationPrincipal ShopUser user) {
		if (user == null) {
			return Mono.just(new Order());
		}
		return this.webClient.get()
				.uri(props.getUserUrl(), b -> b.path("me").build())
				.headers(httpHeaders -> httpHeaders.setBearerAuth(user.getIdToken().getTokenValue()))
				.retrieve()
				.bodyToMono(CustomerResponse.class)
				.map(c -> {
					final Order order = new Order();
					order.setFirstName(c.getFirstName());
					order.setLastName(c.getLastName());
					order.setEmail(c.getEmail());
					if (!CollectionUtils.isEmpty(c.getAddresses())) {
						final CustomerAddressResponse address = c.getAddresses().get(0);
						order.setStreet(address.getStreet());
						order.setNumber(address.getNumber());
						order.setCity(address.getCity());
						order.setPostcode(address.getPostcode());
						order.setCountry(address.getCountry());
					}
					if (!CollectionUtils.isEmpty(c.getCards())) {
						final CustomerCardResponse card = c.getCards().get(0);
						order.setLongNum(card.getLongNum());
						order.setExpires(card.getExpires());
						order.setCcv(card.getCcv());
					}
					return order;
				});
	}

	@GetMapping(path = "checkout", params = "login")
	public String loginForCheckout(WebSession session, UriComponentsBuilder builder) {
		session.getAttributes().put(REDIRECT_TO_ATTR, builder.replacePath("checkout").build().toUri());
		return "redirect:/oauth2/authorization/ui";
	}

	@GetMapping(path = "checkout")
	public Mono<String> checkoutForm(Cart cart, Model model, @RegisteredOAuth2AuthorizedClient("sock") OAuth2AuthorizedClient authorizedClient) {
		if (cart.getItems().isEmpty()) {
			model.addAttribute("errorMessage", "Cart is empty.");
		}
		final Mono<Cart> latestCart = cart.retrieveLatest(props.getCatalogUrl(), this.webClient, authorizedClient);
		model.addAttribute("cart", latestCart);
		return Mono.just("checkout");
	}

	@PostMapping(path = "checkout")
	public Mono<String> checkout(@AuthenticationPrincipal ShopUser user, Cart cart, Model model, Order order, BindingResult bindingResult, @RegisteredOAuth2AuthorizedClient("sock") OAuth2AuthorizedClient authorizedClient, ServerWebExchange exchange) {
		final ConstraintViolations violations = order.validate();
		if (!violations.isValid()) {
			violations.apply(bindingResult::rejectValue);
			return this.checkoutForm(cart, model, authorizedClient);
		}
		final Mono<OrderResponse> orderResponse = user == null ?
				this.orderService.placeOrderWithoutLogin(cart, order, authorizedClient) :
				this.orderService.placeOrderWithLogin(user, cart, order, authorizedClient);
		return orderResponse
				.doOnSuccess(__ -> /* Delete Cookie */
						exchange.getResponse().addCookie(ResponseCookie.from(CART_ID_COOKIE_NAME, "deleted")
								.maxAge(0)
								.httpOnly(true)
								.path("/")
								.build()))
				.thenReturn("redirect:/")
				.onErrorResume(RuntimeException.class, e -> {
					final String message = e.getMessage();
					log.warn(message, e);
					model.addAttribute("errorMessage", message);
					return this.checkoutForm(cart, model, authorizedClient);
				});
	}
}
