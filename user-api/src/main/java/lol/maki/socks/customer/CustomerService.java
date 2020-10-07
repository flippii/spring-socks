package lol.maki.socks.customer;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {
	private final CustomerMapper customerMapper;

	public CustomerService(CustomerMapper customerMapper) {
		this.customerMapper = customerMapper;
	}

	@Transactional
	public int[] createCustomer(Customer customer) throws CustomerDuplicatedException {
		if (this.customerMapper.findByUsername(customer.username()).isPresent()) {
			throw new CustomerDuplicatedException("The requested username is already registered.");
		}
		if (customer.password() != null && this.customerMapper.findByEmail(customer.email()).isPresent()) {
			throw new CustomerDuplicatedException("The requested email is already registered.");
		}
		return this.customerMapper.upsert(customer);
	}

	@Transactional
	public Address addAddress(Customer customer, Address newAddress) {
		final Optional<Address> existing = customer.addresses().stream().filter(newAddress::isSame).findAny();
		if (existing.isPresent()) {
			// do nothing
			return existing.get();
		}
		final Customer newCustomer = ImmutableCustomer.builder()
				.from(customer)
				.addAddresses(newAddress)
				.build();
		this.customerMapper.upsert(newCustomer);
		return newAddress;
	}

	@Transactional
	public Card addCard(Customer customer, Card newCard) {
		final Optional<Card> existing = customer.cards().stream().filter(newCard::isSame).findAny();
		if (existing.isPresent()) {
			// do nothing
			return existing.get();
		}
		final Customer newCustomer = ImmutableCustomer.builder()
				.from(customer)
				.addCards(newCard)
				.build();
		this.customerMapper.upsert(newCustomer);
		return newCard;
	}

	public static class CustomerDuplicatedException extends RuntimeException {
		public CustomerDuplicatedException(String message) {
			super(message);
		}
	}
}
