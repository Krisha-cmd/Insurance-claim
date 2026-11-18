package com.claims.ops.api;

import com.claims.ops.api.dto.ChaosRequest;
import com.claims.ops.api.dto.ChaosStatus;
import com.claims.ops.service.ChaosService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ENDPOINTS.ops.chaos. ADJUSTER only. */
@RestController
@RequestMapping("/api/ops/chaos")
public class ChaosController {

    private final ChaosService chaos;

    public ChaosController(ChaosService chaos) {
        this.chaos = chaos;
    }

    @PostMapping
    public ChaosStatus set(@Valid @RequestBody ChaosRequest body, CurrentUser user) {
        return chaos.set(body, user);
    }

    @GetMapping
    public ChaosStatus get() {
        return chaos.current();
    }
}
