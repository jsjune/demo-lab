package org.example.demolab;

import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/products")
public class ProductController {
    private final Map<Long, Product> products = new HashMap<>();
    private final AtomicLong counter = new AtomicLong();

    @GetMapping
    public List<Product> getAll() {
        return new ArrayList<>(products.values());
    }

    @GetMapping("/{id}")
    public Product getOne(@PathVariable Long id) {
        return products.get(id);
    }

    @PostMapping
    public Product create(@RequestBody Product product) {
        product.setId(counter.incrementAndGet());
        products.put(product.getId(), product);
        return product;
    }

    @PutMapping("/{id}")
    public Product update(@PathVariable Long id, @RequestBody Product product) {
        product.setId(id);
        products.put(id, product);
        return product;
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        products.remove(id);
    }
}
