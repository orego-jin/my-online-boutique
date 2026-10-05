package dev.boutique.inventory;

import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/stock")
public class InventoryController {
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> decreaseScript;

    public InventoryController(StringRedisTemplate redis) {
        this.redis = redis;
        this.decreaseScript = new DefaultRedisScript<>();
        this.decreaseScript.setLocation(new ClassPathResource("lua/decrease-stock.lua"));
        this.decreaseScript.setResultType(Long.class);
    }

    // GET /stock/OLJCESPC7Z : Inventory check
    @GetMapping("/{productId}")
    public ResponseEntity<?> getStock(@PathVariable String productId) {
        String stock = redis.opsForValue().get("stock:" + productId);

        if (stock == null) {
            return ResponseEntity.status(404).body(Map.of("message", "Out of stock."));
        }

        return ResponseEntity.ok(Map.of(
                "productId", productId,
                "available", Long.parseLong(stock)));
    }

    // POST /stock/OLJCESPC7Z/decrease : Decrease 1 from inventory
    @PostMapping("/{productId}/decrease")
    public ResponseEntity<?> decreaseStock(@PathVariable String productId) {
       
        Long remaining = redis.execute(decreaseScript, List.of("stock:" + productId));

        if (remaining == null) {
            return ResponseEntity.internalServerError().body(Map.of("message", "Error: Null product"));
        }
        if (remaining == -2) {
            return ResponseEntity.status(404).body(Map.of("message", "No product available"));
        }
        if (remaining == -1) {
            return ResponseEntity.status(409).body(Map.of("message", "Out of stock."));
        }

        return ResponseEntity.ok(Map.of("productId", productId, "available", remaining));
    }
}
