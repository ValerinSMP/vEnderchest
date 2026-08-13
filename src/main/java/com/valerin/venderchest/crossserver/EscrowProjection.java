package com.valerin.venderchest.crossserver;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** Adds and verifies the two private tags carried only by a volatile cursor projection. */
public final class EscrowProjection {

    private final NamespacedKey escrowId;
    private final NamespacedKey opSequence;
    private final Function<byte[], ItemStack> decoder;

    public EscrowProjection(Plugin plugin) {
        this(plugin, ItemStack::deserializeBytes);
    }

    EscrowProjection(Plugin plugin, Function<byte[], ItemStack> decoder) {
        escrowId = plugin == null ? Objects.requireNonNull(NamespacedKey.fromString("venderchest:escrow_id"))
                : new NamespacedKey(plugin, "escrow_id");
        opSequence = plugin == null
                ? Objects.requireNonNull(NamespacedKey.fromString("venderchest:escrow_op_sequence"))
                : new NamespacedKey(plugin, "escrow_op_sequence");
        this.decoder = Objects.requireNonNull(decoder);
    }

    public ItemStack project(SlotValue canonical, UUID mutationId, long sequence) {
        ItemStack item = decode(canonical);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(escrowId, PersistentDataType.STRING, mutationId.toString());
        meta.getPersistentDataContainer().set(opSequence, PersistentDataType.LONG, sequence);
        item.setItemMeta(meta);
        return item;
    }

    public SlotValue projectionValue(SlotValue canonical, UUID mutationId, long sequence) {
        return value(project(canonical, mutationId, sequence));
    }

    public Optional<Tag> tag(ItemStack item) {
        if (empty(item)) return Optional.empty();
        var pdc = item.getItemMeta().getPersistentDataContainer();
        String id = pdc.get(escrowId, PersistentDataType.STRING);
        Long sequence = pdc.get(opSequence, PersistentDataType.LONG);
        if (id == null && sequence == null) return Optional.empty();
        if (id == null || sequence == null || sequence < 1) throw new IllegalArgumentException("partial escrow tag");
        return Optional.of(new Tag(UUID.fromString(id), sequence));
    }

    public SlotValue canonicalValue(ItemStack projection) {
        if (tag(projection).isEmpty()) throw new IllegalArgumentException("item is not an escrow projection");
        ItemStack clean = projection.clone();
        ItemMeta meta = clean.getItemMeta();
        meta.getPersistentDataContainer().remove(escrowId);
        meta.getPersistentDataContainer().remove(opSequence);
        clean.setItemMeta(meta);
        return value(clean);
    }

    public boolean matches(ItemStack item, CursorEscrow escrow) {
        try {
            return tag(item).filter(tag -> tag.escrowId().equals(escrow.escrowId())
                            && tag.opSequence() == escrow.opSequence()).isPresent()
                    && same(item, escrow.projection())
                    && same(decode(canonicalValue(item)), escrow.canonical());
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    public ItemStack canonicalItem(CursorEscrow escrow) {
        return decode(escrow.canonical());
    }

    /** Verifies identity+sequence for a partial drag projection and strips only our two keys. */
    public ItemStack canonicalPart(ItemStack item, CursorEscrow escrow) {
        Tag actual = tag(item).orElseThrow(() -> new IllegalArgumentException("missing escrow tag"));
        if (!actual.escrowId().equals(escrow.escrowId()) || actual.opSequence() != escrow.opSequence()) {
            throw new IllegalArgumentException("stale escrow projection");
        }
        SlotValue clean = canonicalValue(item);
        ItemStack canonical = canonicalItem(escrow);
        ItemStack part = decode(clean);
        if (!part.isSimilar(canonical) || part.getAmount() > canonical.getAmount()) {
            throw new IllegalArgumentException("escrow projection fingerprint diverged");
        }
        return part;
    }

    public Optional<Integer> exactEscapedSlot(
            CursorEscrow escrow, List<TaggedOccurrence> occurrences) {
        if (occurrences.size() > 1) throw new IllegalStateException("duplicate escrow projection");
        if (occurrences.isEmpty()) return Optional.empty();
        TaggedOccurrence occurrence = occurrences.getFirst();
        if (!occurrence.tag().escrowId().equals(escrow.escrowId())
                || occurrence.tag().opSequence() != escrow.opSequence()
                || !same(occurrence.projection(), escrow.projection())
                || !same(occurrence.canonical(), escrow.canonical())) {
            throw new IllegalStateException("escaped escrow tag diverged");
        }
        return Optional.of(occurrence.slot());
    }

    private ItemStack decode(SlotValue value) {
        byte[] bytes = value.bytes();
        if (bytes.length == 0) throw new IllegalArgumentException("empty escrow item");
        return decoder.apply(bytes);
    }

    private SlotValue value(ItemStack item) {
        return SlotValue.fromBytes(item.clone().serializeAsBytes());
    }

    private boolean same(SlotValue left, SlotValue right) {
        return same(decode(left), right);
    }

    private boolean same(ItemStack live, SlotValue snapshot) {
        ItemStack stored = decode(snapshot);
        return live.getAmount() == stored.getAmount() && live.isSimilar(stored);
    }

    private boolean empty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    public record Tag(UUID escrowId, long opSequence) {
        public Tag { Objects.requireNonNull(escrowId); }
    }

    public record TaggedOccurrence(int slot, Tag tag, SlotValue projection, SlotValue canonical) {
        public TaggedOccurrence {
            if (slot < 0) throw new IllegalArgumentException("negative slot");
            Objects.requireNonNull(tag);
            Objects.requireNonNull(projection);
            Objects.requireNonNull(canonical);
        }
    }
}
