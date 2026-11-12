package com.tamada.travel.orchestrator.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

import static org.hibernate.annotations.UuidGenerator.Style.VERSION_7;

@Entity
@Table(name = "customer_contacts")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor
@Getter
public class CustomerContact {

    @Id
    @UuidGenerator(style = VERSION_7)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "composite_order_id", nullable = false, unique = true)
    private CompositeOrder compositeOrder;

    @Column(nullable = false, length = 50)
    private String phone;

    @Column(nullable = false, length = 255)
    private String email;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public CustomerContact(
            CompositeOrder compositeOrder,
            String phone,
            String email
    ) {
        this.compositeOrder = compositeOrder;
        this.phone = phone;
        this.email = email;
    }
}
